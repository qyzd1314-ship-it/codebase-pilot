package com.yupi.yuaiagent.task.service;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.task.dto.AgentArtifactDto;
import com.yupi.yuaiagent.task.entity.AgentTask;
import com.yupi.yuaiagent.task.enums.AgentArtifactType;
import com.yupi.yuaiagent.task.enums.AgentTaskStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentTaskSummaryService {

    private final AgentArtifactService agentArtifactService;

    public AgentTaskSummaryService(AgentArtifactService agentArtifactService) {
        this.agentArtifactService = agentArtifactService;
    }

    public TaskSummarySnapshot buildSummary(AgentTask task) {
        List<AgentArtifactDto> artifacts = agentArtifactService.listArtifacts(task.getId());
        int artifactCount = artifacts.size();
        int deliverableArtifactCount = (int) artifacts.stream()
                .filter(this::isDeliverableArtifact)
                .count();
        String deliveryStatus = buildDeliveryStatus(task.getStatus(), deliverableArtifactCount);
        String summary = buildTaskSummary(task, artifactCount, deliverableArtifactCount, deliveryStatus);
        return new TaskSummarySnapshot(summary, deliveryStatus, artifactCount, deliverableArtifactCount);
    }

    private String buildDeliveryStatus(AgentTaskStatus status, int deliverableArtifactCount) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case SUCCEEDED -> deliverableArtifactCount > 0 ? "DELIVERED" : "SUCCEEDED_NO_DELIVERABLE";
            case REVIEWING -> "IN_REVIEW";
            case RUNNING, PLANNING -> "IN_PROGRESS";
            case WAITING_APPROVAL -> "WAITING_APPROVAL";
            case WAITING_PLAN_CONFIRMATION -> "WAITING_PLAN_CONFIRMATION";
            case BLOCKED, FAILED -> "NEEDS_ATTENTION";
            case CANCELLED -> "CANCELLED";
            case PENDING -> "NOT_STARTED";
        };
    }

    private String buildTaskSummary(AgentTask task,
                                    int artifactCount,
                                    int deliverableArtifactCount,
                                    String deliveryStatus) {
        return switch (deliveryStatus) {
            case "DELIVERED" -> firstNonBlank(
                    task.getReviewSummary(),
                    "Task delivered successfully with " + deliverableArtifactCount + " final artifact(s)."
            );
            case "SUCCEEDED_NO_DELIVERABLE" -> "Task finished but no final deliverable artifact was detected.";
            case "IN_REVIEW" -> "Execution finished. Reviewer is validating outputs and deliverables.";
            case "IN_PROGRESS" -> task.getCurrentStepSeq() != null
                    ? "Task is running at step " + task.getCurrentStepSeq() + "."
                    : "Task is currently running.";
            case "WAITING_APPROVAL" -> firstNonBlank(task.getErrorMessage(), "Task is waiting for user approval.");
            case "WAITING_PLAN_CONFIRMATION" -> firstNonBlank(task.getPlanDiffSummary(), "Task is waiting for revised plan confirmation.");
            case "NEEDS_ATTENTION" -> firstNonBlank(task.getReviewSummary(), task.getErrorMessage(), "Task needs user attention before it can continue.");
            case "CANCELLED" -> "Task was cancelled before delivery.";
            case "NOT_STARTED" -> "Task is ready to start.";
            default -> firstNonBlank(task.getReviewSummary(), task.getPlanSummary(), "Task summary is not available yet.");
        } + buildArtifactSuffix(artifactCount, deliverableArtifactCount);
    }

    private String buildArtifactSuffix(int artifactCount, int deliverableArtifactCount) {
        if (artifactCount <= 0) {
            return "";
        }
        return " Artifacts: " + artifactCount + " total, " + deliverableArtifactCount + " deliverable.";
    }

    private boolean isDeliverableArtifact(AgentArtifactDto artifact) {
        return artifact != null
                && ("DELIVERABLE".equalsIgnoreCase(artifact.getArtifactType())
                || AgentArtifactType.ROOT_CAUSE_REPORT.name().equalsIgnoreCase(artifact.getArtifactType())
                || AgentArtifactType.CODE_EVIDENCE.name().equalsIgnoreCase(artifact.getArtifactType())
                || "text/markdown".equalsIgnoreCase(artifact.getContentType())
                || StrUtil.blankToDefault(artifact.getArtifactName(), "").toLowerCase().endsWith(".md"));
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (StrUtil.isNotBlank(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    public record TaskSummarySnapshot(String taskSummary,
                                      String deliveryStatus,
                                      Integer artifactCount,
                                      Integer deliverableArtifactCount) {
    }
}
