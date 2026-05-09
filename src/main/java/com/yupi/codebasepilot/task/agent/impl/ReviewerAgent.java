package com.yupi.codebasepilot.task.agent.impl;

import cn.hutool.core.util.StrUtil;
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
import com.yupi.codebasepilot.task.dto.CodeFlowStepDto;
import com.yupi.codebasepilot.task.dto.CodeModuleDto;
import com.yupi.codebasepilot.task.dto.DiagnosisHypothesisDto;
import com.yupi.codebasepilot.task.dto.EvidenceRefDto;
import com.yupi.codebasepilot.task.dto.ModuleSummaryOutputDto;
import com.yupi.codebasepilot.task.dto.ReviewerDecisionDto;
import com.yupi.codebasepilot.task.enums.CodeUnderstandingIntent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class ReviewerAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are a strict reviewer for codebase analysis results.
            Review the agent output using only the provided user goal, structured output, and evidence references.
            Check:
            1. Whether the result answers the user's question.
            2. Whether the cited evidence supports the claims.
            3. Whether there are unsupported claims or speculation.
            4. Whether any obvious risk or limitation is missing.
            5. Whether the result is safe to deliver to the user.
            Output JSON only.
            JSON schema:
            {
              "passed": true,
              "reason": "...",
              "unsupportedClaims": [],
              "risk": "...",
              "suggestedAction": "DELIVER"
            }
            Allowed suggestedAction values: DELIVER, REPLAN, RETRY, NEED_HUMAN_APPROVAL, FAIL.
            """;

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    public ReviewerAgent(LlmService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "ReviewerAgent";
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<EvidenceRefDto> evidenceRefs = context.getEvidenceRefs() == null ? List.of() : context.getEvidenceRefs();
        ReviewSubject reviewSubject = resolveReviewSubject(context);
        AgentResult ruleDecision = ruleReview(context, reviewSubject, evidenceRefs);
        if (ruleDecision.getNextAction() != NextAction.DELIVER) {
            return ruleDecision;
        }
        if (isRuleBasedPartialApproval(ruleDecision)) {
            return ruleDecision;
        }

        LlmStructuredResponse<ReviewerDecisionDto> response = llmService.chatForObject(
                LlmRequest.builder()
                        .systemPrompt(SYSTEM_PROMPT)
                        .userPrompt(buildUserPrompt(context, reviewSubject, evidenceRefs))
                        .responseFormatHint("Respond with JSON only.")
                        .scene("REVIEW")
                        .metadata(Map.of(
                                "taskId", context.getTaskId(),
                                "repoId", context.getRepoId(),
                                "evidenceCount", evidenceRefs.size(),
                                "businessType", StrUtil.blankToDefault(context.getBusinessType(), "UNKNOWN")
                        ))
                        .build(),
                ReviewerDecisionDto.class
        );
        if (!response.isSuccess()) {
            return fallbackToRuleReview(ruleDecision, response, evidenceRefs);
        }

        ReviewerDecisionDto reviewDecision = normalizeLlmDecision(response.getData(), response.getContent());
        NextAction nextAction = normalizeSuggestedAction(reviewDecision.getSuggestedAction());
        boolean passed = Boolean.TRUE.equals(reviewDecision.getPassed()) && nextAction == NextAction.DELIVER;
        String reviewerReason = defaultText(reviewDecision.getReason(),
                "Reviewer accepted the result after the LLM-assisted review.");
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("passed", passed);
        structuredOutput.put("reviewerReason", reviewerReason);
        structuredOutput.put("unsupportedClaims", defaultList(reviewDecision.getUnsupportedClaims()));
        structuredOutput.put("risk", defaultText(reviewDecision.getRisk(), "No additional delivery risk was identified."));
        structuredOutput.put("nextAction", nextAction.name());
        structuredOutput.put("suggestedAction", nextAction.name());

        return AgentResult.builder()
                .success(passed)
                .summary(reviewerReason)
                .structuredOutput(structuredOutput)
                .evidenceRefs(evidenceRefs)
                .confidence(passed ? 0.9D : 0.45D)
                .nextAction(nextAction)
                .failureReason(passed ? null : reviewerReason)
                .build();
    }

    private AgentResult ruleReview(AgentContext context, ReviewSubject reviewSubject, List<EvidenceRefDto> evidenceRefs) {
        if (evidenceRefs.isEmpty()) {
            return rejected("Reviewer rejected the result because no evidence refs were attached.", NextAction.REPLAN, evidenceRefs);
        }
        boolean hasLineAnchors = evidenceRefs.stream().allMatch(this::hasFileAndLineAnchors);
        if (!hasLineAnchors) {
            return rejected("Reviewer rejected the result because file paths or line ranges are missing.", NextAction.REPLAN, evidenceRefs);
        }
        if (reviewSubject.step() == null) {
            return rejected("Reviewer rejected the result because the upstream agent output is missing.", NextAction.REPLAN, evidenceRefs);
        }
        if ("CODE_UNDERSTANDING".equalsIgnoreCase(context.getBusinessType())) {
            return reviewCodeUnderstanding(reviewSubject.step(), evidenceRefs);
        }
        return reviewDiagnosis(reviewSubject.step(), evidenceRefs);
    }

    private AgentResult reviewDiagnosis(AgentStepSummary diagnosisStep, List<EvidenceRefDto> evidenceRefs) {
        Map<String, Object> structuredOutput = diagnosisStep.getStructuredOutput();
        if (structuredOutput == null || structuredOutput.isEmpty()) {
            return rejected("Reviewer could not verify the diagnosis because structuredOutput is missing.", NextAction.RETRY, evidenceRefs);
        }
        List<DiagnosisHypothesisDto> hypotheses = readHypotheses(structuredOutput);
        if (hypotheses.isEmpty()) {
            return rejected("Reviewer rejected the diagnosis because no grounded hypotheses were produced.", NextAction.REPLAN, evidenceRefs);
        }
        boolean missingEvidenceField = hypotheses.stream()
                .anyMatch(hypothesis -> hypothesis.getEvidence() == null || hypothesis.getEvidence().isEmpty());
        if (missingEvidenceField) {
            return rejected("Reviewer rejected the diagnosis because at least one hypothesis is missing evidence.", NextAction.REPLAN, evidenceRefs);
        }
        boolean invalidEvidenceAnchors = hypotheses.stream()
                .flatMap(hypothesis -> hypothesis.getEvidence().stream())
                .anyMatch(evidence -> !containsFileAndLineReference(evidence));
        if (invalidEvidenceAnchors) {
            return rejected("Reviewer rejected the diagnosis because the hypothesis evidence is missing file paths or line ranges.", NextAction.REPLAN, evidenceRefs);
        }
        if (Boolean.TRUE.equals(structuredOutput.get("needMoreSearch"))) {
            return rejected("Reviewer rejected the diagnosis because the diagnosis itself requested more search.", NextAction.REPLAN, evidenceRefs);
        }
        Double diagnosisConfidence = diagnosisStep.getConfidence();
        if (diagnosisConfidence != null && diagnosisConfidence < 0.5D) {
            return rejected("Reviewer requires human approval because the diagnosis confidence is below 0.5.", NextAction.NEED_HUMAN_APPROVAL, evidenceRefs);
        }
        return accepted("Rule-based review accepted the diagnosis. Running LLM-assisted review for a final delivery decision.",
                "Rule-based review passed.", evidenceRefs, false, List.of(), List.of());
    }

    private AgentResult reviewCodeUnderstanding(AgentStepSummary understandingStep, List<EvidenceRefDto> evidenceRefs) {
        Map<String, Object> structuredOutput = understandingStep.getStructuredOutput();
        if (structuredOutput == null || structuredOutput.isEmpty()) {
            return rejected("Reviewer could not verify the module summary because structuredOutput is missing.", NextAction.RETRY, evidenceRefs);
        }
        ModuleSummaryOutputDto moduleSummary = objectMapper.convertValue(structuredOutput, ModuleSummaryOutputDto.class);
        CodeUnderstandingIntent intent = resolveUnderstandingIntent(moduleSummary);
        List<CodeModuleDto> modules = moduleSummary.getModules() == null ? List.of() : moduleSummary.getModules();
        List<CodeFlowStepDto> flowSteps = moduleSummary.getFlowSteps() == null ? List.of() : moduleSummary.getFlowSteps();
        List<Map<String, Object>> operations = moduleSummary.getOperations() == null ? List.of() : moduleSummary.getOperations();
        List<Map<String, Object>> callChain = moduleSummary.getCallChain() == null ? List.of() : moduleSummary.getCallChain();
        if (requiresModules(intent) && modules.isEmpty()) {
            return rejected("Reviewer rejected the module summary because no grounded modules were produced.", NextAction.REPLAN, evidenceRefs);
        }
        if (intent == CodeUnderstandingIntent.FLOW_ANALYSIS && flowSteps.isEmpty()) {
            return rejected("Reviewer rejected the code understanding result because no grounded flow or call-chain steps were produced.", NextAction.REPLAN, evidenceRefs);
        }
        if (intent == CodeUnderstandingIntent.MODULE_DETAIL && operations.isEmpty()) {
            return rejected("Reviewer rejected the module detail because no grounded operations were produced.", NextAction.REPLAN, evidenceRefs);
        }
        if (intent == CodeUnderstandingIntent.API_CALL_CHAIN && callChain.isEmpty()) {
            return rejected("Reviewer rejected the call chain because no grounded layer chain was produced.", NextAction.REPLAN, evidenceRefs);
        }
        boolean missingGrounding = modules.stream()
                .anyMatch(module -> (module.getEvidence() == null || module.getEvidence().isEmpty())
                        && (module.getKeyFiles() == null || module.getKeyFiles().isEmpty()))
                || flowSteps.stream()
                .anyMatch(step -> (step.getEvidence() == null || step.getEvidence().isEmpty())
                        && (step.getKeyFiles() == null || step.getKeyFiles().isEmpty()))
                || operations.stream().anyMatch(item -> readEvidenceList(item).isEmpty())
                || callChain.stream().anyMatch(item -> readEvidenceList(item).isEmpty());
        if (missingGrounding) {
            return rejected("Reviewer rejected the code understanding result because at least one section is missing evidence or key files.", NextAction.REPLAN, evidenceRefs);
        }
        boolean invalidEvidenceAnchors = modules.stream()
                .filter(module -> module.getEvidence() != null)
                .flatMap(module -> module.getEvidence().stream())
                .anyMatch(evidence -> !containsFileAndLineReference(evidence))
                || flowSteps.stream()
                .filter(step -> step.getEvidence() != null)
                .flatMap(step -> step.getEvidence().stream())
                .anyMatch(evidence -> !containsFileAndLineReference(evidence))
                || operations.stream()
                .flatMap(item -> readEvidenceList(item).stream())
                .anyMatch(evidence -> !containsFileAndLineReference(evidence))
                || callChain.stream()
                .flatMap(item -> readEvidenceList(item).stream())
                .anyMatch(evidence -> !containsFileAndLineReference(evidence));
        if (invalidEvidenceAnchors) {
            return rejected("Reviewer rejected the code understanding result because the evidence is missing file paths or line ranges.", NextAction.REPLAN, evidenceRefs);
        }
        if (Boolean.TRUE.equals(moduleSummary.getNeedMoreSearch())) {
            return accepted(
                    "Rule-based review accepted the grounded partial code understanding result. Missing information will be surfaced in delivery.",
                    "Rule-based review approved a partial delivery because the current result is grounded but still lists follow-up search gaps.",
                    evidenceRefs,
                    true,
                    moduleSummary.getMissingInfo(),
                    moduleSummary.getSuggestedFollowUpQueries()
            );
        }
        Double confidence = understandingStep.getConfidence();
        if (confidence != null && confidence < 0.45D) {
            return rejected("Reviewer requires human approval because the code understanding confidence is below 0.45.", NextAction.NEED_HUMAN_APPROVAL, evidenceRefs);
        }
        return accepted("Rule-based review accepted the code understanding result. Running LLM-assisted review for a final delivery decision.",
                "Rule-based review passed.", evidenceRefs, false, List.of(), List.of());
    }

    private boolean requiresModules(CodeUnderstandingIntent intent) {
        return intent == CodeUnderstandingIntent.OVERALL_STRUCTURE;
    }

    @SuppressWarnings("unchecked")
    private List<String> readEvidenceList(Map<String, Object> item) {
        Object evidence = item.get("evidence");
        if (!(evidence instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream().map(String::valueOf).toList();
    }

    private CodeUnderstandingIntent resolveUnderstandingIntent(ModuleSummaryOutputDto moduleSummary) {
        if (StrUtil.isBlank(moduleSummary.getIntent())) {
            return CodeUnderstandingIntent.OVERALL_STRUCTURE;
        }
        try {
            return CodeUnderstandingIntent.valueOf(moduleSummary.getIntent());
        } catch (IllegalArgumentException ignored) {
            return CodeUnderstandingIntent.OVERALL_STRUCTURE;
        }
    }

    private AgentResult accepted(String summary,
                                 String reviewerReason,
                                 List<EvidenceRefDto> evidenceRefs,
                                 boolean partial,
                                 List<String> missingInfo,
                                 List<String> suggestedFollowUpQueries) {
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("passed", true);
        structuredOutput.put("partial", partial);
        structuredOutput.put("reviewerReason", reviewerReason);
        structuredOutput.put("unsupportedClaims", List.of());
        structuredOutput.put("risk", partial
                ? "The current result is grounded but incomplete. Missing information is listed for follow-up."
                : "Please verify the highlighted code paths before acting on the result.");
        structuredOutput.put("nextAction", NextAction.DELIVER.name());
        structuredOutput.put("reviewMode", partial ? "RULE_BASED_PARTIAL" : "RULE_BASED");
        structuredOutput.put("missingInfo", missingInfo == null ? List.of() : missingInfo);
        structuredOutput.put("suggestedFollowUpQueries", suggestedFollowUpQueries == null ? List.of() : suggestedFollowUpQueries);
        return AgentResult.builder()
                .success(true)
                .summary(summary)
                .structuredOutput(structuredOutput)
                .evidenceRefs(evidenceRefs)
                .confidence(partial ? 0.72D : 0.85D)
                .nextAction(NextAction.DELIVER)
                .failureReason(null)
                .build();
    }

    private AgentResult fallbackToRuleReview(AgentResult ruleDecision,
                                             LlmStructuredResponse<ReviewerDecisionDto> response,
                                             List<EvidenceRefDto> evidenceRefs) {
        String reason = "Rule-based review passed. LLM review failed, fallback to rule-based review.";
        if (response.getStatus() == LlmCallStatus.DISABLED) {
            reason = "Rule-based review passed. LLM review failed, fallback to rule-based review because the LLM is unavailable.";
        } else if (StrUtil.isNotBlank(response.getErrorMessage())) {
            reason = reason + " Error: " + response.getErrorMessage();
        }
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("passed", true);
        structuredOutput.put("reviewerReason", reason);
        structuredOutput.put("unsupportedClaims", List.of());
        structuredOutput.put("risk", "LLM-assisted review did not complete. Please verify the result carefully before acting on it.");
        structuredOutput.put("nextAction", NextAction.DELIVER.name());
        structuredOutput.put("reviewMode", "RULE_BASED_FALLBACK");
        structuredOutput.put("llmReviewStatus", response.getStatus() == null ? null : response.getStatus().name());
        structuredOutput.put("llmReviewError", response.getErrorMessage());

        return AgentResult.builder()
                .success(true)
                .summary(reason)
                .structuredOutput(structuredOutput)
                .evidenceRefs(evidenceRefs)
                .confidence(Math.max(ruleDecision.getConfidence(), 0.7D))
                .nextAction(NextAction.DELIVER)
                .failureReason(null)
                .build();
    }

    private boolean isRuleBasedPartialApproval(AgentResult ruleDecision) {
        if (ruleDecision == null || ruleDecision.getStructuredOutput() == null) {
            return false;
        }
        return Boolean.TRUE.equals(ruleDecision.getStructuredOutput().get("partial"));
    }

    private ReviewerDecisionDto normalizeLlmDecision(ReviewerDecisionDto output, String rawContent) {
        ReviewerDecisionDto effective = output == null ? new ReviewerDecisionDto() : output;
        if (effective.getUnsupportedClaims() == null) {
            effective.setUnsupportedClaims(List.of());
        }
        if (StrUtil.isBlank(effective.getReason()) && StrUtil.isNotBlank(rawContent)) {
            effective.setReason(rawContent);
        }
        if (effective.getPassed() == null) {
            effective.setPassed(Boolean.FALSE);
        }
        if (StrUtil.isBlank(effective.getSuggestedAction())) {
            effective.setSuggestedAction(effective.getPassed() ? NextAction.DELIVER.name() : NextAction.NEED_HUMAN_APPROVAL.name());
        }
        return effective;
    }

    private String buildUserPrompt(AgentContext context,
                                   ReviewSubject reviewSubject,
                                   List<EvidenceRefDto> evidenceRefs) {
        String evidenceBlock = evidenceRefs.stream()
                .map(this::formatEvidence)
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
        return """
                User goal:
                %s

                Business type:
                %s

                Upstream agent:
                %s

                Result summary:
                %s

                Structured output:
                %s

                Grounded evidence refs:
                %s

                Review whether the result is grounded, answers the goal, and is safe to deliver.
                """.formatted(
                defaultText(context.getUserGoal(), "(empty)"),
                defaultText(context.getBusinessType(), "(unknown)"),
                defaultText(reviewSubject.agentName(), "(unknown)"),
                defaultText(reviewSubject.step() == null ? null : reviewSubject.step().getSummary(), "(empty)"),
                reviewSubject.step() == null ? "{}" : objectToJson(reviewSubject.step().getStructuredOutput()),
                defaultText(evidenceBlock, "(none)")
        );
    }

    private ReviewSubject resolveReviewSubject(AgentContext context) {
        List<AgentStepSummary> previousSteps = context.getPreviousSteps() == null ? List.of() : context.getPreviousSteps();
        if ("CODE_UNDERSTANDING".equalsIgnoreCase(context.getBusinessType())) {
            AgentStepSummary understandingStep = previousSteps.stream()
                    .filter(step -> "CodeUnderstandingAgent".equals(step.getAssignedAgent()))
                    .reduce((first, second) -> second)
                    .orElse(null);
            return new ReviewSubject("CodeUnderstandingAgent", understandingStep);
        }
        AgentStepSummary diagnosisStep = previousSteps.stream()
                .filter(step -> "DiagnosisAgent".equals(step.getAssignedAgent()))
                .reduce((first, second) -> second)
                .orElse(null);
        return new ReviewSubject("DiagnosisAgent", diagnosisStep);
    }

    private List<DiagnosisHypothesisDto> readHypotheses(Map<String, Object> structuredOutput) {
        Object rawHypotheses = structuredOutput.get("hypotheses");
        if (rawHypotheses == null) {
            return List.of();
        }
        return objectMapper.convertValue(rawHypotheses, new TypeReference<List<DiagnosisHypothesisDto>>() {});
    }

    private String formatEvidence(EvidenceRefDto evidence) {
        return "%s:%s-%s | reason: %s | preview: %s".formatted(
                defaultText(evidence.getFilePath(), "(unknown-file)"),
                evidence.getStartLine() == null ? "?" : evidence.getStartLine(),
                evidence.getEndLine() == null ? "?" : evidence.getEndLine(),
                defaultText(evidence.getReason(), "(empty)"),
                defaultText(evidence.getCodePreview(), "(empty)")
        );
    }

    private boolean hasFileAndLineAnchors(EvidenceRefDto evidence) {
        return StrUtil.isNotBlank(evidence.getFilePath())
                && evidence.getStartLine() != null
                && evidence.getEndLine() != null;
    }

    private boolean containsFileAndLineReference(String evidence) {
        if (StrUtil.isBlank(evidence)) {
            return false;
        }
        int colonIndex = evidence.lastIndexOf(':');
        int dashIndex = evidence.lastIndexOf('-');
        if (colonIndex <= 0 || dashIndex <= colonIndex + 1 || dashIndex >= evidence.length() - 1) {
            return false;
        }
        String filePath = evidence.substring(0, colonIndex).trim();
        String startLine = evidence.substring(colonIndex + 1, dashIndex).trim();
        String endLine = evidence.substring(dashIndex + 1).trim();
        return StrUtil.isNotBlank(filePath)
                && startLine.chars().allMatch(Character::isDigit)
                && endLine.chars().allMatch(Character::isDigit);
    }

    private NextAction normalizeSuggestedAction(String suggestedAction) {
        if (StrUtil.isBlank(suggestedAction)) {
            return NextAction.NEED_HUMAN_APPROVAL;
        }
        try {
            NextAction action = NextAction.valueOf(suggestedAction);
            return switch (action) {
                case DELIVER, REPLAN, RETRY, NEED_HUMAN_APPROVAL, FAIL -> action;
                default -> NextAction.NEED_HUMAN_APPROVAL;
            };
        } catch (IllegalArgumentException ignored) {
            return NextAction.NEED_HUMAN_APPROVAL;
        }
    }

    private String defaultText(String value, String defaultValue) {
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    private List<String> defaultList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).collect(Collectors.toCollection(ArrayList::new));
    }

    private AgentResult rejected(String summary, NextAction nextAction, List<EvidenceRefDto> evidenceRefs) {
        return AgentResult.builder()
                .success(false)
                .summary(summary)
                .structuredOutput(Map.of(
                        "passed", false,
                        "reviewerReason", summary,
                        "unsupportedClaims", List.of(),
                        "risk", "The result is not ready for delivery.",
                        "nextAction", nextAction.name(),
                        "reviewMode", "RULE_BASED"
                ))
                .evidenceRefs(evidenceRefs)
                .confidence(nextAction == NextAction.NEED_HUMAN_APPROVAL ? 0.4D : 0.1D)
                .nextAction(nextAction)
                .failureReason(summary)
                .build();
    }

    private String objectToJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private record ReviewSubject(String agentName, AgentStepSummary step) {
    }
}
