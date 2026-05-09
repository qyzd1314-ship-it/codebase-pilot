package com.yupi.yuaiagent.task.agent.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.yupi.yuaiagent.task.dto.CodeFlowStepDto;
import com.yupi.yuaiagent.task.dto.CodeModuleDto;
import com.yupi.yuaiagent.task.dto.EvidenceRefDto;
import com.yupi.yuaiagent.task.dto.ModuleSummaryOutputDto;
import com.yupi.yuaiagent.task.dto.RepoProfileDto;
import com.yupi.yuaiagent.task.dto.UnderstandingPlanDto;
import com.yupi.yuaiagent.task.enums.CodeUnderstandingIntent;
import com.yupi.yuaiagent.task.service.RepoProfiler;
import com.yupi.yuaiagent.task.service.UnderstandingIntentPlanner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CodeUnderstandingAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are a senior codebase understanding agent.
            Summarize the repository using only the provided evidence refs and code snippets.
            Constraints:
            1. Only use the provided files, snippets, and evidence labels.
            2. Do not invent modules, files, classes, flows, or call chains that are not grounded in evidence.
            3. Every conclusion must cite evidence using filePath:startLine-endLine and list keyFiles.
            4. If the evidence is insufficient, set needMoreSearch=true and explain missingInfo.
            5. Output JSON only.

            Output shape depends on intent:
            - OVERALL_STRUCTURE:
              {
                "intent": "OVERALL_STRUCTURE",
                "subType": "MODULE_SUMMARY",
                "summary": "...",
                "modules": [{"name":"...", "responsibility":"...", "keyFiles":["..."], "evidence":["file:1-10"]}],
                "architectureNotes": ["..."],
                "riskNotes": [],
                "needMoreSearch": false,
                "missingInfo": []
              }
            - FLOW_ANALYSIS:
              {
                "intent": "FLOW_ANALYSIS",
                "targetModule": "...",
                "subType": "FLOW_SUMMARY",
                "summary": "...",
                "flowSteps": [{"step":"...", "description":"...", "keyFiles":["..."], "evidence":["file:1-10"]}],
                "riskNotes": ["..."],
                "needMoreSearch": false,
                "missingInfo": []
              }
            - MODULE_DETAIL:
              {
                "intent": "MODULE_DETAIL",
                "targetModule": "...",
                "subType": "MODULE_DETAIL",
                "summary": "...",
                "operations": [{"operation":"query", "controller":"...", "service":"...", "mapper":"...", "evidence":["file:1-10"]}],
                "keyFiles": ["..."],
                "notesAndRisks": ["..."],
                "needMoreSearch": false,
                "missingInfo": []
              }
            - API_CALL_CHAIN:
              {
                "intent": "API_CALL_CHAIN",
                "subType": "CALL_CHAIN",
                "summary": "...",
                "callChain": [{"layer":"Controller", "responsibility":"...", "methods":["..."], "keyFiles":["..."], "evidence":["file:1-10"]}],
                "notesAndRisks": ["..."],
                "needMoreSearch": false,
                "missingInfo": []
              }
            """;

    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final RepoProfiler repoProfiler;
    private final UnderstandingIntentPlanner understandingIntentPlanner;

    public CodeUnderstandingAgent(LlmService llmService,
                                  ObjectMapper objectMapper,
                                  RepoProfiler repoProfiler,
                                  UnderstandingIntentPlanner understandingIntentPlanner) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.repoProfiler = repoProfiler;
        this.understandingIntentPlanner = understandingIntentPlanner;
    }

    @Override
    public String name() {
        return "CodeUnderstandingAgent";
    }

    @Override
    public AgentResult run(AgentContext context) {
        RepoProfileDto repoProfile = resolveRepoProfile(context);
        UnderstandingPlanDto understandingPlan = resolveUnderstandingPlan(context, repoProfile);
        CodeUnderstandingIntent intent = resolveUnderstandingIntent(understandingPlan);
        List<EvidenceRefDto> evidenceRefs = context.getEvidenceRefs() == null ? List.of() : context.getEvidenceRefs();
        if (evidenceRefs.isEmpty()) {
            Map<String, Object> structuredOutput = new LinkedHashMap<>();
            structuredOutput.put("intent", intent.name());
            structuredOutput.put("targetModule", understandingPlan == null ? null : understandingPlan.getTargetModule());
            structuredOutput.put("subType", understandingPlan == null ? defaultSubType(intent) : understandingPlan.getOutputSchema());
            structuredOutput.put("outputSchema", understandingPlan == null ? defaultSubType(intent) : understandingPlan.getOutputSchema());
            structuredOutput.put("deliveryMode", "BLOCKED");
            structuredOutput.put("summary", "No code understanding output was generated.");
            structuredOutput.put("modules", List.of());
            structuredOutput.put("flowSteps", List.of());
            structuredOutput.put("operations", List.of());
            structuredOutput.put("callChain", List.of());
            structuredOutput.put("architectureNotes", List.of());
            structuredOutput.put("riskNotes", List.of());
            structuredOutput.put("notesAndRisks", List.of());
            structuredOutput.put("needMoreSearch", true);
            structuredOutput.put("partial", false);
            structuredOutput.put("confirmedScope", List.of());
            structuredOutput.put("missingInfo", List.of("Missing code evidence for project understanding."));
            structuredOutput.put("suggestedFollowUpQueries", buildSuggestedFollowUpQueries(understandingPlan));
            return AgentResult.builder()
                    .success(false)
                    .summary("Project structure analysis cannot start because no grounded code evidence is available.")
                    .structuredOutput(structuredOutput)
                    .evidenceRefs(List.of())
                    .confidence(0.05D)
                    .nextAction(NextAction.REPLAN)
                    .failureReason("Missing code evidence for project understanding.")
                    .build();
        }

        LlmStructuredResponse<ModuleSummaryOutputDto> response = llmService.chatForObject(
                LlmRequest.builder()
                        .systemPrompt(SYSTEM_PROMPT)
                        .userPrompt(buildUserPrompt(context, evidenceRefs, repoProfile, understandingPlan, intent))
                        .responseFormatHint("Respond with JSON only.")
                        .scene("CODE_UNDERSTANDING")
                        .metadata(Map.of(
                                "taskId", context.getTaskId(),
                                "repoId", context.getRepoId(),
                                "intent", intent.name(),
                                "evidenceCount", evidenceRefs.size()
                        ))
                        .build(),
                ModuleSummaryOutputDto.class
        );
        if (!response.isSuccess()) {
            return handleLlmFailure(response, evidenceRefs, intent, understandingPlan);
        }

        ModuleSummaryOutputDto output = normalizeOutput(response.getData(), response.getContent(), understandingPlan, intent);
        if (Boolean.TRUE.equals(output.getNeedMoreSearch())) {
            if (hasGroundedDeliverableContent(output)) {
                output.setPartial(true);
                output.setDeliveryMode("PARTIAL");
                output.setConfirmedScope(buildConfirmedScope(output));
                if (output.getSuggestedFollowUpQueries() == null || output.getSuggestedFollowUpQueries().isEmpty()) {
                    output.setSuggestedFollowUpQueries(buildSuggestedFollowUpQueries(understandingPlan));
                }
                return AgentResult.builder()
                        .success(true)
                        .summary(defaultText(output.getSummary(),
                                "Generated a grounded partial code understanding result from the available evidence."))
                        .structuredOutput(toStructuredOutput(output))
                        .evidenceRefs(evidenceRefs)
                        .confidence(normalizeConfidence(output))
                        .nextAction(NextAction.CONTINUE)
                        .failureReason(null)
                        .build();
            }
            return AgentResult.builder()
                    .success(false)
                    .summary(defaultText(output.getSummary(),
                            "The current evidence is still insufficient to complete a grounded code understanding result."))
                    .structuredOutput(toStructuredOutput(output))
                    .evidenceRefs(evidenceRefs)
                    .confidence(normalizeConfidence(output))
                    .nextAction(NextAction.REPLAN)
                    .failureReason("The current evidence is still insufficient to complete a grounded code understanding result.")
                    .build();
        }

        return AgentResult.builder()
                .success(true)
                .summary(defaultText(output.getSummary(), "Generated a grounded code understanding result from the provided evidence."))
                .structuredOutput(toStructuredOutput(output))
                .evidenceRefs(evidenceRefs)
                .confidence(normalizeConfidence(output))
                .nextAction(NextAction.CONTINUE)
                .failureReason(null)
                .build();
    }

    private AgentResult handleLlmFailure(LlmStructuredResponse<ModuleSummaryOutputDto> response,
                                         List<EvidenceRefDto> evidenceRefs,
                                         CodeUnderstandingIntent intent,
                                         UnderstandingPlanDto understandingPlan) {
        ModuleSummaryOutputDto recoveredOutput = recoverOutputFromRawContent(response.getContent(), understandingPlan, intent);
        if (recoveredOutput != null) {
            Map<String, Object> structuredOutput = new LinkedHashMap<>(toStructuredOutput(recoveredOutput));
            structuredOutput.put("parseRecovered", true);
            structuredOutput.put("parseRecoveryMode", "RAW_JSON_EXTRACTION");
            if (response.getErrorMessage() != null && !response.getErrorMessage().isBlank()) {
                structuredOutput.put("parseRecoverySourceError", response.getErrorMessage());
            }
            return buildRecoveredResult(recoveredOutput, structuredOutput, evidenceRefs);
        }

        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("intent", intent.name());
        structuredOutput.put("subType", defaultSubType(intent));
        structuredOutput.put("outputSchema", defaultSubType(intent));
        structuredOutput.put("summary", "Code understanding output is unavailable.");
        structuredOutput.put("modules", List.of());
        structuredOutput.put("flowSteps", List.of());
        structuredOutput.put("operations", List.of());
        structuredOutput.put("callChain", List.of());
        structuredOutput.put("architectureNotes", List.of());
        structuredOutput.put("riskNotes", List.of());
        structuredOutput.put("notesAndRisks", List.of());
        structuredOutput.put("needMoreSearch", false);
        structuredOutput.put("partial", false);
        structuredOutput.put("confirmedScope", List.of());
        structuredOutput.put("missingInfo", List.of());
        structuredOutput.put("suggestedFollowUpQueries", List.of());
        structuredOutput.put("deliveryMode", "BLOCKED");
        structuredOutput.put("llmStatus", response.getStatus() == null ? null : response.getStatus().name());
        if (response.getContent() != null && !response.getContent().isBlank()) {
            structuredOutput.put("rawContent", response.getContent());
            structuredOutput.put("parseError", response.getErrorMessage());
            return AgentResult.builder()
                    .success(false)
                    .summary("Code understanding produced raw LLM output that could not be safely parsed.")
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
                    .summary("Code understanding could not run because the LLM is currently unavailable.")
                    .structuredOutput(structuredOutput)
                    .evidenceRefs(evidenceRefs)
                    .confidence(0.1D)
                    .nextAction(NextAction.NEED_HUMAN_APPROVAL)
                    .failureReason("LLM is unavailable. Configure an API key or review the repository manually.")
                    .build();
        }
        return AgentResult.builder()
                .success(false)
                .summary("Code understanding could not call the LLM successfully.")
                .structuredOutput(structuredOutput)
                .evidenceRefs(evidenceRefs)
                .confidence(0.1D)
                .nextAction(NextAction.RETRY)
                .failureReason("LLM call failed: " + defaultText(response.getErrorMessage(), "unknown error"))
                .build();
    }

    private AgentResult buildRecoveredResult(ModuleSummaryOutputDto output,
                                             Map<String, Object> structuredOutput,
                                             List<EvidenceRefDto> evidenceRefs) {
        if (Boolean.TRUE.equals(output.getNeedMoreSearch())) {
            if (hasGroundedDeliverableContent(output)) {
                output.setPartial(true);
                output.setDeliveryMode("PARTIAL");
                output.setConfirmedScope(buildConfirmedScope(output));
                structuredOutput = new LinkedHashMap<>(toStructuredOutput(output));
                structuredOutput.put("parseRecovered", true);
                structuredOutput.put("parseRecoveryMode", "RAW_JSON_EXTRACTION");
                return AgentResult.builder()
                        .success(true)
                        .summary(defaultText(output.getSummary(),
                                "Recovered a grounded partial code understanding result from raw LLM content."))
                        .structuredOutput(structuredOutput)
                        .evidenceRefs(evidenceRefs)
                        .confidence(normalizeConfidence(output))
                        .nextAction(NextAction.CONTINUE)
                        .failureReason(null)
                        .build();
            }
            return AgentResult.builder()
                    .success(false)
                    .summary(defaultText(output.getSummary(),
                            "Recovered code understanding output still requires more search before it can be grounded."))
                    .structuredOutput(structuredOutput)
                    .evidenceRefs(evidenceRefs)
                    .confidence(normalizeConfidence(output))
                    .nextAction(NextAction.REPLAN)
                    .failureReason("Recovered code understanding output still requires more grounded evidence.")
                    .build();
        }
        return AgentResult.builder()
                .success(true)
                .summary(defaultText(output.getSummary(), "Recovered a grounded code understanding result from raw LLM content."))
                .structuredOutput(structuredOutput)
                .evidenceRefs(evidenceRefs)
                .confidence(normalizeConfidence(output))
                .nextAction(NextAction.CONTINUE)
                .failureReason(null)
                .build();
    }

    private String buildUserPrompt(AgentContext context,
                                   List<EvidenceRefDto> evidenceRefs,
                                   RepoProfileDto repoProfile,
                                   UnderstandingPlanDto understandingPlan,
                                   CodeUnderstandingIntent intent) {
        String previousSummaries = context.getPreviousSteps() == null || context.getPreviousSteps().isEmpty()
                ? "(none)"
                : context.getPreviousSteps().stream()
                .map(this::formatPreviousStep)
                .collect(Collectors.joining(System.lineSeparator()));
        String evidenceBlock = evidenceRefs.stream()
                .map(this::formatEvidence)
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
        String searchContext = readSearchContext(context);
        String repoProfileSummary = repoProfile == null ? "(none)" : objectMapper.valueToTree(repoProfile).toPrettyString();

        return """
                User goal:
                %s

                Repo id:
                %s

                Understanding intent:
                %s

                Search context:
                %s

                Understanding plan:
                %s

                Repo profile:
                %s

                Previous successful step summaries:
                %s

                Grounded code evidence:
                %s

                Focus requirements for this intent:
                %s
                """.formatted(
                defaultText(context.getUserGoal(), "(empty)"),
                defaultText(context.getRepoId(), "(unknown)"),
                intent.name(),
                searchContext,
                understandingPlan == null ? "(none)" : objectToJson(understandingPlan),
                repoProfileSummary,
                previousSummaries,
                evidenceBlock,
                intentSpecificGuidance(intent)
        );
    }

    private String intentSpecificGuidance(CodeUnderstandingIntent intent) {
        return switch (intent) {
            case OVERALL_STRUCTURE -> "Summarize the major backend modules, their responsibilities, and the layered architecture.";
            case FLOW_ANALYSIS -> "Describe the key runtime or business flow using grounded flow steps, configuration touch points, and any missing links.";
            case MODULE_DETAIL -> "Describe the target module structure, especially CRUD-style operations and the Controller / Service / Mapper responsibilities.";
            case API_CALL_CHAIN -> "Describe the concrete Controller -> Service -> Mapper call chain visible in the evidence.";
        };
    }

    private String readSearchContext(AgentContext context) {
        Object searchOutput = context.getMemory() == null ? null : context.getMemory().get("CodeSearchAgentOutput");
        if (!(searchOutput instanceof Map<?, ?> searchMap)) {
            return "(none)";
        }
        Object intent = searchMap.get("understandingIntent");
        Object queries = searchMap.get("searchQueries");
        return "intent=%s, queries=%s".formatted(
                intent == null ? "(unknown)" : intent,
                queries == null ? "(none)" : queries
        );
    }

    private RepoProfileDto resolveRepoProfile(AgentContext context) {
        Object profile = context.getMemory() == null ? null : context.getMemory().get("repoProfile");
        if (profile instanceof RepoProfileDto dto) {
            return dto;
        }
        return repoProfiler.buildProfile(context.getRepoId());
    }

    private UnderstandingPlanDto resolveUnderstandingPlan(AgentContext context, RepoProfileDto repoProfile) {
        Object plan = context.getMemory() == null ? null : context.getMemory().get("understandingPlan");
        if (plan instanceof UnderstandingPlanDto dto) {
            return dto;
        }
        Object searchOutput = context.getMemory() == null ? null : context.getMemory().get("CodeSearchAgentOutput");
        if (searchOutput instanceof Map<?, ?> searchMap && searchMap.get("understandingPlan") != null) {
            return objectMapper.convertValue(searchMap.get("understandingPlan"), UnderstandingPlanDto.class);
        }
        return understandingIntentPlanner.plan(context.getUserGoal(), repoProfile);
    }

    private CodeUnderstandingIntent resolveUnderstandingIntent(UnderstandingPlanDto understandingPlan) {
        if (understandingPlan != null && understandingPlan.getIntent() != null) {
            try {
                return CodeUnderstandingIntent.valueOf(understandingPlan.getIntent());
            } catch (IllegalArgumentException ignored) {
                // Fallback below.
            }
        }
        return CodeUnderstandingIntent.OVERALL_STRUCTURE;
    }

    private String formatPreviousStep(AgentStepSummary step) {
        return "- %s (%s): %s".formatted(
                defaultText(step.getStepTitle(), step.getAssignedAgent()),
                defaultText(step.getAssignedAgent(), "unknown-agent"),
                defaultText(step.getSummary(), "(empty)")
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
                defaultText(evidenceRef.getReason(), "(empty)"),
                evidenceRef.getScore() == null ? "(unknown)" : String.format("%.2f", evidenceRef.getScore()),
                defaultText(evidenceRef.getCodePreview(), "(empty)")
        );
    }

    private String formatEvidenceLabel(EvidenceRefDto evidenceRef) {
        return "%s:%s-%s".formatted(
                defaultText(evidenceRef.getFilePath(), "(unknown-file)"),
                evidenceRef.getStartLine() == null ? "?" : evidenceRef.getStartLine(),
                evidenceRef.getEndLine() == null ? "?" : evidenceRef.getEndLine()
        );
    }

    private ModuleSummaryOutputDto recoverOutputFromRawContent(String rawContent,
                                                               UnderstandingPlanDto understandingPlan,
                                                               CodeUnderstandingIntent intent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        List<String> candidates = List.of(
                rawContent,
                stripCodeFence(rawContent),
                extractJsonObject(rawContent),
                extractJsonObject(stripCodeFence(rawContent))
        );
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            ModuleSummaryOutputDto recovered = tryParseModuleSummary(candidate);
            if (recovered != null) {
                return normalizeOutput(recovered, candidate, understandingPlan, intent);
            }
        }
        return null;
    }

    private ModuleSummaryOutputDto tryParseModuleSummary(String candidate) {
        try {
            return objectMapper.readValue(candidate, ModuleSummaryOutputDto.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String stripCodeFence(String rawContent) {
        String normalized = rawContent.trim();
        if (normalized.startsWith("```")) {
            int firstNewLine = normalized.indexOf('\n');
            if (firstNewLine >= 0) {
                normalized = normalized.substring(firstNewLine + 1);
            }
        }
        if (normalized.endsWith("```")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized.trim();
    }

    private String extractJsonObject(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        int start = rawContent.indexOf('{');
        int end = rawContent.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return rawContent.substring(start, end + 1).trim();
    }

    private ModuleSummaryOutputDto normalizeOutput(ModuleSummaryOutputDto output,
                                                   String rawContent,
                                                   UnderstandingPlanDto understandingPlan,
                                                   CodeUnderstandingIntent intent) {
        ModuleSummaryOutputDto effective = output == null ? new ModuleSummaryOutputDto() : output;
        effective.setIntent(defaultText(effective.getIntent(), intent.name()));
        effective.setTargetModule(defaultText(effective.getTargetModule(), understandingPlan == null ? null : understandingPlan.getTargetModule()));
        effective.setSubType(defaultText(effective.getSubType(), understandingPlan == null ? defaultSubType(intent) : understandingPlan.getOutputSchema()));
        effective.setOutputSchema(defaultText(effective.getOutputSchema(), understandingPlan == null ? defaultSubType(intent) : understandingPlan.getOutputSchema()));
        if (effective.getDeliveryMode() == null || effective.getDeliveryMode().isBlank()) {
            effective.setDeliveryMode(Boolean.TRUE.equals(effective.getNeedMoreSearch()) ? "PARTIAL" : "FINAL");
        }
        if (effective.getModules() == null) {
            effective.setModules(List.of());
        }
        if (effective.getFlowSteps() == null) {
            effective.setFlowSteps(List.of());
        }
        if (effective.getOperations() == null) {
            effective.setOperations(List.of());
        }
        if (effective.getCallChain() == null) {
            effective.setCallChain(List.of());
        }
        if (effective.getArchitectureNotes() == null) {
            effective.setArchitectureNotes(List.of());
        }
        if (effective.getRiskNotes() == null) {
            effective.setRiskNotes(List.of());
        }
        if (effective.getNotesAndRisks() == null) {
            effective.setNotesAndRisks(List.of());
        }
        if (effective.getMissingInfo() == null) {
            effective.setMissingInfo(List.of());
        }
        if (effective.getNeedMoreSearch() == null) {
            effective.setNeedMoreSearch(false);
        }
        if (effective.getPartial() == null) {
            effective.setPartial(Boolean.TRUE.equals(effective.getNeedMoreSearch()));
        }
        if (effective.getConfirmedScope() == null) {
            effective.setConfirmedScope(buildConfirmedScope(effective));
        }
        if (effective.getSuggestedFollowUpQueries() == null) {
            effective.setSuggestedFollowUpQueries(buildSuggestedFollowUpQueries(understandingPlan));
        }
        if ((effective.getSummary() == null || effective.getSummary().isBlank()) && rawContent != null && !rawContent.isBlank()) {
            effective.setSummary(rawContent);
        }
        return effective;
    }

    private boolean hasGroundedDeliverableContent(ModuleSummaryOutputDto output) {
        boolean hasSummary = output.getSummary() != null && !output.getSummary().isBlank();
        if (!hasSummary) {
            return false;
        }
        boolean hasModules = output.getModules() != null && output.getModules().stream()
                .anyMatch(module -> module.getEvidence() != null && !module.getEvidence().isEmpty());
        boolean hasFlowSteps = output.getFlowSteps() != null && output.getFlowSteps().stream()
                .anyMatch(step -> step.getEvidence() != null && !step.getEvidence().isEmpty());
        boolean hasOperations = output.getOperations() != null && output.getOperations().stream()
                .anyMatch(item -> item.get("evidence") instanceof List<?> rawList && !rawList.isEmpty());
        boolean hasCallChain = output.getCallChain() != null && output.getCallChain().stream()
                .anyMatch(item -> item.get("evidence") instanceof List<?> rawList && !rawList.isEmpty());
        return hasModules || hasFlowSteps || hasOperations || hasCallChain;
    }

    private List<String> buildConfirmedScope(ModuleSummaryOutputDto output) {
        LinkedHashMap<String, Boolean> confirmed = new LinkedHashMap<>();
        if (output.getModules() != null) {
            output.getModules().stream()
                    .filter(module -> module.getEvidence() != null && !module.getEvidence().isEmpty())
                    .map(module -> "Confirmed module: " + defaultText(module.getName(), "Unnamed module"))
                    .forEach(label -> confirmed.put(label, Boolean.TRUE));
        }
        if (output.getFlowSteps() != null) {
            output.getFlowSteps().stream()
                    .filter(step -> step.getEvidence() != null && !step.getEvidence().isEmpty())
                    .map(step -> "Confirmed flow step: " + defaultText(step.getStep(), "Unspecified step"))
                    .forEach(label -> confirmed.put(label, Boolean.TRUE));
        }
        if (output.getOperations() != null) {
            output.getOperations().stream()
                    .filter(item -> item.get("evidence") instanceof List<?> rawList && !rawList.isEmpty())
                    .map(item -> "Confirmed operation: " + defaultText(String.valueOf(item.get("operation")), "Operation"))
                    .forEach(label -> confirmed.put(label, Boolean.TRUE));
        }
        if (output.getCallChain() != null) {
            output.getCallChain().stream()
                    .filter(item -> item.get("evidence") instanceof List<?> rawList && !rawList.isEmpty())
                    .map(item -> "Confirmed layer: " + defaultText(String.valueOf(item.get("layer")), "Layer"))
                    .forEach(label -> confirmed.put(label, Boolean.TRUE));
        }
        return List.copyOf(confirmed.keySet());
    }

    private List<String> buildSuggestedFollowUpQueries(UnderstandingPlanDto understandingPlan) {
        LinkedHashMap<String, Boolean> queries = new LinkedHashMap<>();
        if (understandingPlan != null && understandingPlan.getTargetModule() != null && !understandingPlan.getTargetModule().isBlank()) {
            queries.put(understandingPlan.getTargetModule(), Boolean.TRUE);
        }
        if (understandingPlan != null && understandingPlan.getTargetKeywords() != null) {
            understandingPlan.getTargetKeywords().stream()
                    .filter(keyword -> keyword != null && !keyword.isBlank())
                    .limit(6)
                    .forEach(keyword -> queries.put(keyword, Boolean.TRUE));
        }
        if (queries.isEmpty()) {
            queries.put("controller", Boolean.TRUE);
            queries.put("service", Boolean.TRUE);
            queries.put("mapper", Boolean.TRUE);
        }
        return List.copyOf(queries.keySet());
    }

    private String defaultSubType(CodeUnderstandingIntent intent) {
        return switch (intent) {
            case OVERALL_STRUCTURE -> "MODULE_SUMMARY";
            case FLOW_ANALYSIS -> "FLOW_SUMMARY";
            case MODULE_DETAIL -> "MODULE_DETAIL";
            case API_CALL_CHAIN -> "CALL_CHAIN";
        };
    }

    private Map<String, Object> toStructuredOutput(ModuleSummaryOutputDto output) {
        return objectMapper.convertValue(output, new TypeReference<>() {
        });
    }

    private double normalizeConfidence(ModuleSummaryOutputDto output) {
        long groundedEntries = 0;
        long totalEntries = 0;
        if (output.getModules() != null && !output.getModules().isEmpty()) {
            totalEntries += output.getModules().size();
            groundedEntries += output.getModules().stream()
                    .filter(module -> module.getEvidence() != null && !module.getEvidence().isEmpty())
                    .count();
        }
        if (output.getFlowSteps() != null && !output.getFlowSteps().isEmpty()) {
            totalEntries += output.getFlowSteps().size();
            groundedEntries += output.getFlowSteps().stream()
                    .filter(step -> step.getEvidence() != null && !step.getEvidence().isEmpty())
                    .count();
        }
        if (output.getOperations() != null && !output.getOperations().isEmpty()) {
            totalEntries += output.getOperations().size();
            groundedEntries += output.getOperations().stream()
                    .filter(item -> {
                        Object evidence = item.get("evidence");
                        return evidence instanceof List<?> rawList && !rawList.isEmpty();
                    })
                    .count();
        }
        if (output.getCallChain() != null && !output.getCallChain().isEmpty()) {
            totalEntries += output.getCallChain().size();
            groundedEntries += output.getCallChain().stream()
                    .filter(item -> {
                        Object evidence = item.get("evidence");
                        return evidence instanceof List<?> rawList && !rawList.isEmpty();
                    })
                    .count();
        }
        if (totalEntries == 0) {
            return 0.35D;
        }
        double ratio = (double) groundedEntries / totalEntries;
        return Math.max(0.25D, Math.min(0.95D, 0.45D + ratio * 0.45D));
    }

    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String objectToJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
