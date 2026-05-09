package com.yupi.codebasepilot.task.agent.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.codebasepilot.llm.LlmService;
import com.yupi.codebasepilot.llm.dto.LlmCallStatus;
import com.yupi.codebasepilot.llm.dto.LlmRequest;
import com.yupi.codebasepilot.llm.dto.LlmStructuredResponse;
import com.yupi.codebasepilot.task.agent.Agent;
import com.yupi.codebasepilot.task.agent.AgentContext;
import com.yupi.codebasepilot.task.agent.AgentResult;
import com.yupi.codebasepilot.task.agent.AgentStepSummary;
import com.yupi.codebasepilot.task.agent.NextAction;
import com.yupi.codebasepilot.task.dto.DiagnosisHypothesisDto;
import com.yupi.codebasepilot.task.dto.DiagnosisOutputDto;
import com.yupi.codebasepilot.task.dto.EvidenceRefDto;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DiagnosisAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are a senior code diagnosis agent.
            You must analyze the user's bug report using only the provided code evidence.
            Constraints:
            1. Only use the provided evidenceRefs and code snippets.
            2. Do not invent files, classes, functions, or control flow that are not present in the evidence.
            3. Every hypothesis must cite evidence in the format filePath:startLine-endLine.
            4. If the evidence is insufficient, set needMoreSearch=true and fill missingInfo with specific missing signals.
            5. Output JSON only.
            JSON schema:
            {
              "summary": "...",
              "hypotheses": [
                {
                  "cause": "...",
                  "evidence": ["AuthFilter.java:42-58"],
                  "confidence": 0.78,
                  "risk": "..."
                }
              ],
              "needMoreSearch": false,
              "missingInfo": []
            }
            """;

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public DiagnosisAgent(LlmService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "DiagnosisAgent";
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<EvidenceRefDto> evidenceRefs = context.getEvidenceRefs() == null ? List.of() : context.getEvidenceRefs();
        if (evidenceRefs.isEmpty()) {
            return AgentResult.builder()
                    .success(false)
                    .summary("Diagnosis skipped because no grounded code evidence is available.")
                    .structuredOutput(Map.of(
                            "summary", "No diagnosis was generated.",
                            "hypotheses", List.of(),
                            "needMoreSearch", true,
                            "missingInfo", List.of("Code evidence is empty, so the diagnosis cannot identify the relevant class, method, or exception path.")
                    ))
                    .evidenceRefs(List.of())
                    .confidence(0.05D)
                    .nextAction(NextAction.REPLAN)
                    .failureReason("Missing code evidence, so a grounded diagnosis cannot be generated.")
                    .build();
        }

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .userPrompt(buildUserPrompt(context, evidenceRefs))
                .responseFormatHint("Respond with JSON only.")
                .scene("DIAGNOSIS")
                .metadata(Map.of(
                        "taskId", context.getTaskId(),
                        "repoId", context.getRepoId(),
                        "evidenceCount", evidenceRefs.size()
                ))
                .build();
        LlmStructuredResponse<DiagnosisOutputDto> response = llmService.chatForObject(request, DiagnosisOutputDto.class);
        if (!response.isSuccess()) {
            return handleLlmFailure(response, evidenceRefs);
        }

        DiagnosisOutputDto diagnosisOutput = normalizeOutput(response.getData(), response.getContent());
        if (Boolean.TRUE.equals(diagnosisOutput.getNeedMoreSearch())) {
            return AgentResult.builder()
                    .success(false)
                    .summary(defaultSummary(diagnosisOutput.getSummary(),
                            "Diagnosis determined that the current evidence is insufficient for a reliable conclusion."))
                    .structuredOutput(toStructuredOutput(diagnosisOutput))
                    .evidenceRefs(evidenceRefs)
                    .confidence(normalizeConfidence(diagnosisOutput.getHypotheses()))
                    .nextAction(NextAction.REPLAN)
                    .failureReason("Evidence is insufficient and more code search is required.")
                    .build();
        }

        return AgentResult.builder()
                .success(true)
                .summary(defaultSummary(diagnosisOutput.getSummary(),
                        "Diagnosis generated grounded root cause hypotheses from the provided evidence."))
                .structuredOutput(toStructuredOutput(diagnosisOutput))
                .evidenceRefs(evidenceRefs)
                .confidence(normalizeConfidence(diagnosisOutput.getHypotheses()))
                .nextAction(NextAction.CONTINUE)
                .failureReason(null)
                .build();
    }

    private AgentResult handleLlmFailure(LlmStructuredResponse<DiagnosisOutputDto> response, List<EvidenceRefDto> evidenceRefs) {
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("summary", "Diagnosis output is unavailable.");
        structuredOutput.put("hypotheses", List.of());
        structuredOutput.put("needMoreSearch", false);
        structuredOutput.put("missingInfo", List.of());
        structuredOutput.put("llmStatus", response.getStatus() == null ? null : response.getStatus().name());
        if (response.getContent() != null && !response.getContent().isBlank()) {
            structuredOutput.put("rawContent", response.getContent());
            structuredOutput.put("parseError", response.getErrorMessage());
            return AgentResult.builder()
                    .success(false)
                    .summary("Diagnosis produced a raw LLM output that could not be safely parsed.")
                    .structuredOutput(structuredOutput)
                    .evidenceRefs(evidenceRefs)
                    .confidence(0.2D)
                    .nextAction(NextAction.NEED_HUMAN_APPROVAL)
                    .failureReason("LLM output could not be parsed as JSON and requires human review.")
                    .build();
        }
        structuredOutput.put("error", response.getErrorMessage());
        if (response.getStatus() == LlmCallStatus.DISABLED) {
            return AgentResult.builder()
                    .success(false)
                    .summary("Diagnosis could not run because the LLM is currently unavailable.")
                    .structuredOutput(structuredOutput)
                    .evidenceRefs(evidenceRefs)
                    .confidence(0.1D)
                    .nextAction(NextAction.NEED_HUMAN_APPROVAL)
                    .failureReason("LLM is unavailable. Configure an API key or review the diagnosis manually.")
                    .build();
        }
        return AgentResult.builder()
                .success(false)
                .summary("Diagnosis could not call the LLM successfully.")
                .structuredOutput(structuredOutput)
                .evidenceRefs(evidenceRefs)
                .confidence(0.1D)
                .nextAction(NextAction.RETRY)
                .failureReason("LLM call failed: " + defaultSummary(response.getErrorMessage(), "unknown error"))
                .build();
    }

    private DiagnosisOutputDto normalizeOutput(DiagnosisOutputDto output, String rawContent) {
        DiagnosisOutputDto effective = output == null ? new DiagnosisOutputDto() : output;
        if (effective.getHypotheses() == null) {
            effective.setHypotheses(List.of());
        }
        if (effective.getMissingInfo() == null) {
            effective.setMissingInfo(List.of());
        }
        if (effective.getNeedMoreSearch() == null) {
            effective.setNeedMoreSearch(false);
        }
        if ((effective.getSummary() == null || effective.getSummary().isBlank()) && rawContent != null && !rawContent.isBlank()) {
            effective.setSummary(rawContent);
        }
        return effective;
    }

    private Map<String, Object> toStructuredOutput(DiagnosisOutputDto output) {
        return objectMapper.convertValue(output, new TypeReference<>() {
        });
    }

    private double normalizeConfidence(List<DiagnosisHypothesisDto> hypotheses) {
        if (hypotheses == null || hypotheses.isEmpty()) {
            return 0.35D;
        }
        double average = hypotheses.stream()
                .map(DiagnosisHypothesisDto::getConfidence)
                .filter(value -> value != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.55D);
        return Math.max(0.2D, Math.min(0.95D, average));
    }

    private String buildUserPrompt(AgentContext context, List<EvidenceRefDto> evidenceRefs) {
        String previousSummaries = context.getPreviousSteps() == null || context.getPreviousSteps().isEmpty()
                ? "(none)"
                : context.getPreviousSteps().stream()
                .map(this::formatPreviousStep)
                .collect(Collectors.joining(System.lineSeparator()));
        String evidenceBlock = evidenceRefs.stream()
                .map(this::formatEvidence)
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));

        return """
                User goal:
                %s

                Repo id:
                %s

                Previous successful step summaries:
                %s

                Grounded code evidence:
                %s

                Please produce a root cause diagnosis in JSON only.
                Remember:
                - Every hypothesis must cite evidence using filePath:startLine-endLine
                - Do not invent missing files, classes, methods, or runtime behavior
                - If the evidence is insufficient, set needMoreSearch=true and explain missingInfo
                """.formatted(
                defaultSummary(context.getUserGoal(), "(empty)"),
                defaultSummary(context.getRepoId(), "(unknown)"),
                previousSummaries,
                evidenceBlock
        );
    }

    private String formatPreviousStep(AgentStepSummary step) {
        return "- %s (%s): %s".formatted(
                defaultSummary(step.getStepTitle(), step.getAssignedAgent()),
                defaultSummary(step.getAssignedAgent(), "unknown-agent"),
                defaultSummary(step.getSummary(), "(empty)")
        );
    }

    private String formatEvidence(EvidenceRefDto evidenceRef) {
        return """
                Evidence: %s
                reason: %s
                score: %s
                code:
                %s
                """.formatted(
                formatEvidenceLabel(evidenceRef),
                defaultSummary(evidenceRef.getReason(), "(empty)"),
                evidenceRef.getScore() == null ? "(unknown)" : String.format("%.2f", evidenceRef.getScore()),
                defaultSummary(evidenceRef.getCodePreview(), "(empty)")
        );
    }

    private String formatEvidenceLabel(EvidenceRefDto evidenceRef) {
        return "%s:%s-%s".formatted(
                defaultSummary(evidenceRef.getFilePath(), "(unknown-file)"),
                evidenceRef.getStartLine() == null ? "?" : evidenceRef.getStartLine(),
                evidenceRef.getEndLine() == null ? "?" : evidenceRef.getEndLine()
        );
    }

    private String defaultSummary(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
