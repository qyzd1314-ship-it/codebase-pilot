package com.yupi.yuaiagent.task.agent.impl;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.llm.LlmService;
import com.yupi.yuaiagent.llm.dto.LlmCallStatus;
import com.yupi.yuaiagent.llm.dto.LlmRequest;
import com.yupi.yuaiagent.llm.dto.LlmStructuredResponse;
import com.yupi.yuaiagent.task.agent.Agent;
import com.yupi.yuaiagent.task.agent.AgentContext;
import com.yupi.yuaiagent.task.agent.AgentResult;
import com.yupi.yuaiagent.task.agent.AgentStepSummary;
import com.yupi.yuaiagent.task.agent.NextAction;
import com.yupi.yuaiagent.task.dto.EvidenceRefDto;
import com.yupi.yuaiagent.task.dto.PatchPlanOutputDto;
import com.yupi.yuaiagent.task.entity.AgentTask;
import com.yupi.yuaiagent.task.repository.AgentTaskRepository;
import com.yupi.yuaiagent.task.service.AgentArtifactService;
import com.yupi.yuaiagent.task.service.AgentTaskWorkspaceService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PatchAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are a senior patch planning agent.
            Generate a safe patch suggestion using only the provided grounded diagnosis and evidence.
            Constraints:
            1. Only use the provided evidenceRefs, code snippets, diagnosis summary, and root cause hypotheses.
            2. Do not invent files, classes, methods, frameworks, or behaviors that are not present in the evidence.
            3. Do not suggest modifications to unrelated files.
            4. The first version must not modify files, create commits, or open pull requests.
            5. You must explicitly describe risks and include concrete test suggestions.
            6. If the diagnosis is insufficient for a grounded patch, set needMoreInfo=true and explain missingInfo.
            7. Output JSON only.
            JSON schema:
            {
              "filesToChange": ["src/main/java/.../AuthFilter.java"],
              "patchPlan": "...",
              "diffPreview": "...",
              "testSuggestions": ["..."],
              "risks": ["..."],
              "needMoreInfo": false,
              "missingInfo": []
            }
            """;

    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final AgentTaskRepository agentTaskRepository;
    private final AgentTaskWorkspaceService agentTaskWorkspaceService;
    private final AgentArtifactService agentArtifactService;

    public PatchAgent(LlmService llmService,
                      ObjectMapper objectMapper,
                      AgentTaskRepository agentTaskRepository,
                      AgentTaskWorkspaceService agentTaskWorkspaceService,
                      AgentArtifactService agentArtifactService) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.agentTaskRepository = agentTaskRepository;
        this.agentTaskWorkspaceService = agentTaskWorkspaceService;
        this.agentArtifactService = agentArtifactService;
    }

    @Override
    public String name() {
        return "PatchAgent";
    }

    @Override
    public AgentResult run(AgentContext context) {
        AgentStepSummary diagnosisStep = findLatestStep(context, "DiagnosisAgent");
        List<EvidenceRefDto> evidenceRefs = context.getEvidenceRefs() == null ? List.of() : context.getEvidenceRefs();
        if (diagnosisStep == null) {
            return AgentResult.builder()
                    .success(false)
                    .summary("Patch planning requires a grounded diagnosis step.")
                    .structuredOutput(Map.of(
                            "filesToChange", List.of(),
                            "patchPlan", "",
                            "diffPreview", "",
                            "testSuggestions", List.of(),
                            "risks", List.of("No diagnosis result is available yet."),
                            "needMoreInfo", true,
                            "missingInfo", List.of("Run DiagnosisAgent first and confirm the root cause.")
                    ))
                    .evidenceRefs(List.of())
                    .confidence(0.1D)
                    .nextAction(NextAction.NEED_HUMAN_APPROVAL)
                    .failureReason("PatchAgent requires a diagnosis result before proposing a patch.")
                    .build();
        }
        if (evidenceRefs.isEmpty()) {
            return AgentResult.builder()
                    .success(false)
                    .summary("Patch planning was skipped because no grounded evidence is available.")
                    .structuredOutput(Map.of(
                            "filesToChange", List.of(),
                            "patchPlan", "",
                            "diffPreview", "",
                            "testSuggestions", List.of(),
                            "risks", List.of("No evidenceRefs are available to ground a patch."),
                            "needMoreInfo", true,
                            "missingInfo", List.of("Code search returned no grounded evidence for the suspected fix.")
                    ))
                    .evidenceRefs(List.of())
                    .confidence(0.1D)
                    .nextAction(NextAction.REPLAN)
                    .failureReason("PatchAgent requires grounded evidence before generating a patch plan.")
                    .build();
        }

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .userPrompt(buildUserPrompt(context, diagnosisStep, evidenceRefs))
                .responseFormatHint("Respond with JSON only.")
                .scene("PATCH_PLAN")
                .metadata(Map.of(
                        "taskId", context.getTaskId(),
                        "repoId", context.getRepoId(),
                        "evidenceCount", evidenceRefs.size()
                ))
                .build();
        LlmStructuredResponse<PatchPlanOutputDto> response = llmService.chatForObject(request, PatchPlanOutputDto.class);
        if (!response.isSuccess()) {
            return handleLlmFailure(response, evidenceRefs);
        }

        PatchPlanOutputDto patchPlan = normalizeOutput(response.getData(), response.getContent());
        if (Boolean.TRUE.equals(patchPlan.getNeedMoreInfo())) {
            return AgentResult.builder()
                    .success(false)
                    .summary(defaultString(patchPlan.getPatchPlan(), "Patch planning still needs more grounded information."))
                    .structuredOutput(toStructuredOutput(patchPlan))
                    .evidenceRefs(evidenceRefs)
                    .confidence(0.3D)
                    .nextAction(NextAction.NEED_HUMAN_APPROVAL)
                    .failureReason("Patch planning needs more grounded information before proposing a safe patch.")
                    .build();
        }

        AgentTask task = agentTaskRepository.findById(context.getTaskId())
                .orElseThrow(() -> new EntityNotFoundException("Task not found: " + context.getTaskId()));
        File patchArtifactFile = agentTaskWorkspaceService.resolveWorkspaceFile(task, "artifacts/patch-plan.json");
        FileUtil.writeString(writePrettyJson(patchPlan), patchArtifactFile, StandardCharsets.UTF_8);
        agentArtifactService.upsertPatchPlanArtifact(
                task.getId(),
                context.getStepId(),
                "patch-plan.json",
                "artifacts/patch-plan.json",
                "Structured patch plan generated by PatchAgent. This plan does not modify source files automatically.",
                patchPlan,
                evidenceRefs,
                patchArtifactFile
        );

        return AgentResult.builder()
                .success(true)
                .summary(defaultString(patchPlan.getPatchPlan(), "Patch plan generated from the grounded diagnosis."))
                .structuredOutput(toStructuredOutput(patchPlan))
                .evidenceRefs(evidenceRefs)
                .confidence(0.88D)
                .nextAction(NextAction.CONTINUE)
                .failureReason(null)
                .build();
    }

    private AgentResult handleLlmFailure(LlmStructuredResponse<PatchPlanOutputDto> response, List<EvidenceRefDto> evidenceRefs) {
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("filesToChange", List.of());
        structuredOutput.put("patchPlan", "");
        structuredOutput.put("diffPreview", "");
        structuredOutput.put("testSuggestions", List.of());
        structuredOutput.put("risks", List.of());
        structuredOutput.put("needMoreInfo", true);
        structuredOutput.put("missingInfo", List.of());
        structuredOutput.put("llmStatus", response.getStatus() == null ? null : response.getStatus().name());
        if (response.getContent() != null && !response.getContent().isBlank()) {
            structuredOutput.put("rawContent", response.getContent());
            structuredOutput.put("parseError", response.getErrorMessage());
            return AgentResult.builder()
                    .success(false)
                    .summary("PatchAgent produced a raw LLM output that could not be safely parsed.")
                    .structuredOutput(structuredOutput)
                    .evidenceRefs(evidenceRefs)
                    .confidence(0.2D)
                    .nextAction(NextAction.NEED_HUMAN_APPROVAL)
                    .failureReason("LLM output could not be parsed as a patch plan JSON and requires human review.")
                    .build();
        }
        structuredOutput.put("error", response.getErrorMessage());
        if (response.getStatus() == LlmCallStatus.DISABLED) {
            return AgentResult.builder()
                    .success(false)
                    .summary("PatchAgent could not run because the LLM is currently unavailable.")
                    .structuredOutput(structuredOutput)
                    .evidenceRefs(evidenceRefs)
                    .confidence(0.1D)
                    .nextAction(NextAction.NEED_HUMAN_APPROVAL)
                    .failureReason("LLM is unavailable. Configure the provider or review the patch manually.")
                    .build();
        }
        return AgentResult.builder()
                .success(false)
                .summary("PatchAgent could not call the LLM successfully.")
                .structuredOutput(structuredOutput)
                .evidenceRefs(evidenceRefs)
                .confidence(0.1D)
                .nextAction(NextAction.RETRY)
                .failureReason("LLM call failed: " + defaultString(response.getErrorMessage(), "unknown error"))
                .build();
    }

    private PatchPlanOutputDto normalizeOutput(PatchPlanOutputDto output, String rawContent) {
        PatchPlanOutputDto effective = output == null ? new PatchPlanOutputDto() : output;
        if (effective.getFilesToChange() == null) {
            effective.setFilesToChange(List.of());
        }
        if (effective.getTestSuggestions() == null) {
            effective.setTestSuggestions(List.of());
        }
        if (effective.getRisks() == null) {
            effective.setRisks(List.of());
        }
        if (effective.getMissingInfo() == null) {
            effective.setMissingInfo(List.of());
        }
        if (effective.getNeedMoreInfo() == null) {
            effective.setNeedMoreInfo(false);
        }
        if ((effective.getPatchPlan() == null || effective.getPatchPlan().isBlank()) && rawContent != null && !rawContent.isBlank()) {
            effective.setPatchPlan(rawContent);
        }
        if (effective.getDiffPreview() == null) {
            effective.setDiffPreview("");
        }
        return effective;
    }

    private Map<String, Object> toStructuredOutput(PatchPlanOutputDto output) {
        return objectMapper.convertValue(output, new TypeReference<>() {
        });
    }

    private String buildUserPrompt(AgentContext context, AgentStepSummary diagnosisStep, List<EvidenceRefDto> evidenceRefs) {
        AgentStepSummary reviewerStep = findLatestStep(context, "ReviewerAgent");
        String diagnosisJson = writePrettyJson(diagnosisStep.getStructuredOutput());
        String reviewerJson = reviewerStep == null ? "(none)" : writePrettyJson(reviewerStep.getStructuredOutput());
        String evidenceBlock = evidenceRefs.stream()
                .map(this::formatEvidence)
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));

        return """
                User goal:
                %s

                Repo id:
                %s

                Diagnosis summary:
                %s

                Diagnosis structured output:
                %s

                Reviewer structured output:
                %s

                Repo hints:
                %s

                Grounded code evidence:
                %s

                Produce a safe patch plan in JSON only.
                Remember:
                - Only propose changes for files supported by evidenceRefs
                - Do not invent new files or unrelated helpers
                - Include risks and test suggestions
                - This version must not modify files automatically
                """.formatted(
                defaultString(context.getUserGoal(), "(empty)"),
                defaultString(context.getRepoId(), "(unknown)"),
                defaultString(diagnosisStep.getSummary(), "(none)"),
                diagnosisJson,
                reviewerJson,
                inferRepoHints(evidenceRefs),
                evidenceBlock
        );
    }

    private AgentStepSummary findLatestStep(AgentContext context, String agentName) {
        if (context.getPreviousSteps() == null) {
            return null;
        }
        return context.getPreviousSteps().stream()
                .filter(step -> agentName.equals(step.getAssignedAgent()))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private String inferRepoHints(List<EvidenceRefDto> evidenceRefs) {
        boolean javaRepo = evidenceRefs.stream().anyMatch(ref -> defaultString(ref.getFilePath(), "").endsWith(".java"));
        boolean springRepo = evidenceRefs.stream().anyMatch(ref -> defaultString(ref.getCodePreview(), "").contains("@GetMapping")
                || defaultString(ref.getFilePath(), "").toLowerCase().contains("controller"));
        return "language=%s, framework=%s".formatted(
                javaRepo ? "java" : "unknown",
                springRepo ? "spring-boot-like" : "unknown"
        );
    }

    private String formatEvidence(EvidenceRefDto evidenceRef) {
        return """
                Evidence: %s:%s-%s
                reason: %s
                score: %s
                code:
                %s
                """.formatted(
                defaultString(evidenceRef.getFilePath(), "(unknown-file)"),
                evidenceRef.getStartLine() == null ? "?" : evidenceRef.getStartLine(),
                evidenceRef.getEndLine() == null ? "?" : evidenceRef.getEndLine(),
                defaultString(evidenceRef.getReason(), "(empty)"),
                evidenceRef.getScore() == null ? "(unknown)" : String.format("%.2f", evidenceRef.getScore()),
                defaultString(evidenceRef.getCodePreview(), "(empty)")
        );
    }

    private String writePrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize patch payload.", e);
        }
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
