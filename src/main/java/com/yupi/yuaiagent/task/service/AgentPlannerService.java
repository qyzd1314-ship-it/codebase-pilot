package com.yupi.yuaiagent.task.service;

import com.yupi.yuaiagent.task.entity.AgentTask;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentPlannerService {

    private final AgentToolPolicyService agentToolPolicyService;

    public AgentPlannerService(AgentToolPolicyService agentToolPolicyService) {
        this.agentToolPolicyService = agentToolPolicyService;
    }

    public PlanResult createInitialPlan(AgentTask task) {
        AgentToolPolicyService.ToolPolicy contextTool = agentToolPolicyService.getPolicy("task_context_reader");
        AgentToolPolicyService.ToolPolicy systemTool = agentToolPolicyService.getPolicy("terminal_command");
        AgentToolPolicyService.ToolPolicy deliveryTool = agentToolPolicyService.getPolicy("final_response_writer");
        List<PlannedStep> steps = List.of(
                PlannedStep.builder()
                        .stepSeq(1)
                        .title("Understand the goal")
                        .stepType("ANALYSIS")
                        .requiresApproval(contextTool.approvalRequired())
                        .toolName(contextTool.toolName())
                        .toolCategory(contextTool.category())
                        .riskLevel(contextTool.riskLevel())
                        .build(),
                PlannedStep.builder()
                        .stepSeq(2)
                        .title("Draft the first execution summary")
                        .stepType("EXECUTION")
                        .requiresApproval(systemTool.approvalRequired() && !Boolean.TRUE.equals(task.getAutoApproveLowRisk()))
                        .toolName(systemTool.toolName())
                        .toolCategory(systemTool.category())
                        .riskLevel(systemTool.riskLevel())
                        .build(),
                PlannedStep.builder()
                        .stepSeq(3)
                        .title("Finalize and deliver result")
                        .stepType("DELIVERY")
                        .requiresApproval(deliveryTool.approvalRequired())
                        .toolName(deliveryTool.toolName())
                        .toolCategory(deliveryTool.category())
                        .riskLevel(deliveryTool.riskLevel())
                        .build()
        );
        String inheritedContext = task.getInheritedContextSummary() == null ? "" : """
                Inherited context:
                %s

                """.formatted(task.getInheritedContextSummary());
        String summary = """
                %s1. Understand the goal and scope.
                2. Reuse any inherited context or prior deliverables when they help.
                3. Run a first execution pass and collect a structured summary.
                4. Finalize the initial deliverable for the user.
                """.formatted(inheritedContext).trim();
        return new PlanResult(summary, steps);
    }

    public PlanResult createReplan(AgentTask task) {
        AgentToolPolicyService.ToolPolicy contextTool = agentToolPolicyService.getPolicy("task_context_reader");
        AgentToolPolicyService.ToolPolicy fileTool = agentToolPolicyService.getPolicy("file_writer");
        AgentToolPolicyService.ToolPolicy deliveryTool = agentToolPolicyService.getPolicy("final_response_writer");
        String reviewSummary = task.getReviewSummary() == null ? "No reviewer summary available." : task.getReviewSummary();
        String suggestedAction = task.getReviewSuggestedAction() == null ? "MANUAL_FIX" : task.getReviewSuggestedAction();
        List<PlannedStep> steps = List.of(
                PlannedStep.builder()
                        .stepSeq(1)
                        .title("Re-read task goal and reviewer feedback")
                        .stepType("REPLAN_ANALYSIS")
                        .requiresApproval(contextTool.approvalRequired())
                        .toolName(contextTool.toolName())
                        .toolCategory(contextTool.category())
                        .riskLevel(contextTool.riskLevel())
                        .build(),
                PlannedStep.builder()
                        .stepSeq(2)
                        .title("Produce a corrected draft based on reviewer suggestions")
                        .stepType("REMEDIATION")
                        .requiresApproval(fileTool.approvalRequired() && !Boolean.TRUE.equals(task.getAutoApproveLowRisk()))
                        .toolName(fileTool.toolName())
                        .toolCategory(fileTool.category())
                        .riskLevel(fileTool.riskLevel())
                        .build(),
                PlannedStep.builder()
                        .stepSeq(3)
                        .title("Finalize the replanned deliverable")
                        .stepType("REDELIVERY")
                        .requiresApproval(deliveryTool.approvalRequired())
                        .toolName(deliveryTool.toolName())
                        .toolCategory(deliveryTool.category())
                        .riskLevel(deliveryTool.riskLevel())
                        .build()
        );
        String inheritedContext = task.getInheritedContextSummary() == null ? "" : """
                Inherited context:
                %s
                """.formatted(task.getInheritedContextSummary());
        String summary = """
                Replan generated after reviewer feedback.
                Suggested action: %s
                Reviewer summary: %s
                %s
                1. Re-check the goal, inherited context, and failed review reason.
                2. Produce a corrected draft or artifact update.
                3. Deliver a revised final result for another review pass.
                """.formatted(suggestedAction, reviewSummary, inheritedContext).trim();
        return new PlanResult(summary, steps);
    }

    @Value
    public static class PlanResult {
        String summary;
        List<PlannedStep> steps;
    }

    @Value
    @Builder
    public static class PlannedStep {
        Integer stepSeq;
        String title;
        String stepType;
        Boolean requiresApproval;
        String toolName;
        String toolCategory;
        String riskLevel;
    }
}
