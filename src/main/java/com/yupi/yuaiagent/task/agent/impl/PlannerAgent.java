package com.yupi.yuaiagent.task.agent.impl;

import com.yupi.yuaiagent.task.agent.Agent;
import com.yupi.yuaiagent.task.agent.AgentContext;
import com.yupi.yuaiagent.task.agent.AgentResult;
import com.yupi.yuaiagent.task.agent.NextAction;
import com.yupi.yuaiagent.task.dto.RepoProfileDto;
import com.yupi.yuaiagent.task.dto.UnderstandingPlanDto;
import com.yupi.yuaiagent.task.enums.CodeUnderstandingIntent;
import com.yupi.yuaiagent.task.service.RepoProfiler;
import com.yupi.yuaiagent.task.service.UnderstandingIntentPlanner;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PlannerAgent implements Agent {

    private final RepoProfiler repoProfiler;
    private final UnderstandingIntentPlanner understandingIntentPlanner;

    public PlannerAgent(RepoProfiler repoProfiler, UnderstandingIntentPlanner understandingIntentPlanner) {
        this.repoProfiler = repoProfiler;
        this.understandingIntentPlanner = understandingIntentPlanner;
    }

    @Override
    public String name() {
        return "PlannerAgent";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String businessType = context.getBusinessType();
        RepoProfileDto repoProfile = "CODE_UNDERSTANDING".equalsIgnoreCase(businessType) && context.getRepoId() != null
                ? repoProfiler.buildProfile(context.getRepoId()) : null;
        UnderstandingPlanDto understandingPlan = "CODE_UNDERSTANDING".equalsIgnoreCase(businessType)
                ? understandingIntentPlanner.plan(context.getUserGoal(), repoProfile) : null;
        List<PlannedAgentStep> steps = buildSteps(businessType);
        return AgentResult.builder()
                .success(true)
                .summary(workflowSummary(defaultWorkflowName(businessType)))
                .structuredOutput(Map.of(
                        "workflow", defaultWorkflowName(businessType),
                        "codeUnderstandingIntent", understandingPlan == null ? CodeUnderstandingIntent.OVERALL_STRUCTURE.name() : understandingPlan.getIntent(),
                        "understandingPlan", understandingPlan == null ? Map.of() : understandingPlan,
                        "steps", steps.stream().map(PlannedAgentStep::toMap).toList()
                ))
                .evidenceRefs(List.of())
                .confidence(0.95)
                .nextAction(NextAction.CONTINUE)
                .failureReason(null)
                .build();
    }

    public List<PlannedAgentStep> buildSteps(String businessType) {
        if ("CODE_UNDERSTANDING".equalsIgnoreCase(businessType)) {
            return buildCodeUnderstandingSteps();
        }
        if ("PATCH_SUGGESTION".equalsIgnoreCase(businessType)) {
            return buildPatchSuggestionSteps();
        }
        return buildBugDiagnosisSteps();
    }

    public List<PlannedAgentStep> buildCodeUnderstandingSteps() {
        return List.of(
                PlannedAgentStep.builder()
                        .stepTitle("Search structure and flow evidence")
                        .stepType("CODE_SEARCH")
                        .assignedAgent("CodeSearchAgent")
                        .toolName("CodeSearchAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(2)
                        .build(),
                PlannedAgentStep.builder()
                        .stepTitle("Summarize code understanding result")
                        .stepType("CODE_UNDERSTANDING")
                        .assignedAgent("CodeUnderstandingAgent")
                        .toolName("CodeUnderstandingAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(1)
                        .build(),
                PlannedAgentStep.builder()
                        .stepTitle("Review evidence and summary")
                        .stepType("REVIEW")
                        .assignedAgent("ReviewerAgent")
                        .toolName("ReviewerAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(1)
                        .build(),
                PlannedAgentStep.builder()
                        .stepTitle("Generate final deliverables")
                        .stepType("DELIVERY")
                        .assignedAgent("DeliveryAgent")
                        .toolName("DeliveryAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(1)
                        .build()
        );
    }

    public List<PlannedAgentStep> buildBugDiagnosisSteps() {
        return List.of(
                PlannedAgentStep.builder()
                        .stepTitle("Search related code")
                        .stepType("CODE_SEARCH")
                        .assignedAgent("CodeSearchAgent")
                        .toolName("CodeSearchAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(2)
                        .build(),
                PlannedAgentStep.builder()
                        .stepTitle("Analyze possible causes")
                        .stepType("DIAGNOSIS")
                        .assignedAgent("DiagnosisAgent")
                        .toolName("DiagnosisAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(1)
                        .build(),
                PlannedAgentStep.builder()
                        .stepTitle("Review evidence and diagnosis")
                        .stepType("REVIEW")
                        .assignedAgent("ReviewerAgent")
                        .toolName("ReviewerAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(1)
                        .build(),
                PlannedAgentStep.builder()
                        .stepTitle("Generate final deliverables")
                        .stepType("DELIVERY")
                        .assignedAgent("DeliveryAgent")
                        .toolName("DeliveryAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(1)
                        .build()
        );
    }

    public List<PlannedAgentStep> buildPatchSuggestionSteps() {
        return List.of(
                PlannedAgentStep.builder()
                        .stepTitle("Search related code")
                        .stepType("CODE_SEARCH")
                        .assignedAgent("CodeSearchAgent")
                        .toolName("CodeSearchAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(2)
                        .build(),
                PlannedAgentStep.builder()
                        .stepTitle("Analyze possible causes")
                        .stepType("DIAGNOSIS")
                        .assignedAgent("DiagnosisAgent")
                        .toolName("DiagnosisAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(1)
                        .build(),
                PlannedAgentStep.builder()
                        .stepTitle("Review evidence and diagnosis")
                        .stepType("REVIEW")
                        .assignedAgent("ReviewerAgent")
                        .toolName("ReviewerAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(1)
                        .build(),
                PlannedAgentStep.builder()
                        .stepTitle("Generate patch plan")
                        .stepType("PATCH")
                        .assignedAgent("PatchAgent")
                        .toolName("PatchAgent")
                        .toolCategory("AGENT")
                        .riskLevel("MEDIUM")
                        .maxRetry(1)
                        .build(),
                PlannedAgentStep.builder()
                        .stepTitle("Generate final deliverables")
                        .stepType("DELIVERY")
                        .assignedAgent("DeliveryAgent")
                        .toolName("DeliveryAgent")
                        .toolCategory("AGENT")
                        .riskLevel("LOW")
                        .maxRetry(1)
                        .build()
        );
    }

    private String defaultWorkflowName(String businessType) {
        return businessType == null || businessType.isBlank() ? "BUG_DIAGNOSIS" : businessType.trim().toUpperCase();
    }

    private String workflowSummary(String workflowName) {
        return switch (workflowName) {
            case "CODE_UNDERSTANDING" ->
                    "Generated a fixed CODE_UNDERSTANDING workflow with code search, module summary, review, and delivery.";
            case "PATCH_SUGGESTION" ->
                    "Generated a fixed PATCH_SUGGESTION workflow with code search, diagnosis, review, patch planning, and delivery.";
            default ->
                    "Generated a fixed BUG_DIAGNOSIS workflow with code search, diagnosis, review, and delivery.";
        };
    }

    @Value
    @Builder
    public static class PlannedAgentStep {
        String stepTitle;
        String stepType;
        String assignedAgent;
        String toolName;
        String toolCategory;
        String riskLevel;
        Integer maxRetry;

        public Map<String, Object> toMap() {
            return Map.of(
                    "stepTitle", stepTitle,
                    "stepType", stepType,
                    "assignedAgent", assignedAgent,
                    "toolName", toolName,
                    "toolCategory", toolCategory,
                    "riskLevel", riskLevel,
                    "maxRetry", maxRetry
            );
        }
    }
}
