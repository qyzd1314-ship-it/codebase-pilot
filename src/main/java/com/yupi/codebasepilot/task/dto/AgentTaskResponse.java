package com.yupi.codebasepilot.task.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class AgentTaskResponse {

    Long taskId;
    String taskNo;
    String title;
    String goal;
    String taskType;
    String repoId;
    String repoName;
    String repoUrl;
    String businessType;
    String status;
    Boolean autoApproveLowRisk;
    String workspacePath;
    Long sourceTaskId;
    String sourceTaskNo;
    String sourceTaskTitle;
    String sourceTaskRelation;
    String inheritedContextSummary;
    Integer currentStepSeq;
    String planSummary;
    String previousPlanSummary;
    String planDiffSummary;
    String finalResult;
    String handoffContextSummary;
    String taskSummary;
    String deliveryStatus;
    Integer artifactCount;
    Integer deliverableArtifactCount;
    String reviewSummary;
    String reviewSuggestedAction;
    Integer reviewSuggestedStepSeq;
    Integer replanCount;
    Integer consecutiveSameReasonReplanCount;
    Integer maxConsecutiveSameReasonReplanCount;
    String lastReplanReason;
    String errorMessage;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    LocalDateTime startedAt;
    LocalDateTime finishedAt;
    List<AgentPlanSnapshotStepDto> previousPlanSteps;
    List<AgentPlanDiffItemDto> planDiffItems;
    List<AgentArtifactDto> artifacts;
    List<AgentTaskStepDto> steps;
    List<AgentApprovalDto> approvals;
    List<AgentToolCallDto> toolCalls;
    List<AgentTaskEventDto> events;
}
