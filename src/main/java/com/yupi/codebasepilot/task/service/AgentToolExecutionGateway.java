package com.yupi.codebasepilot.task.service;

import com.yupi.codebasepilot.task.entity.AgentTask;
import com.yupi.codebasepilot.task.entity.AgentTaskStep;
import com.yupi.codebasepilot.task.entity.AgentToolCall;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;

@Service
public class AgentToolExecutionGateway {

    private final AgentToolCallService agentToolCallService;
    private final AgentTaskWorkspaceService agentTaskWorkspaceService;
    private final AgentSafeWorkspaceToolService agentSafeWorkspaceToolService;
    private final AgentArtifactService agentArtifactService;

    public AgentToolExecutionGateway(AgentToolCallService agentToolCallService,
                                     AgentTaskWorkspaceService agentTaskWorkspaceService,
                                     AgentSafeWorkspaceToolService agentSafeWorkspaceToolService,
                                     AgentArtifactService agentArtifactService) {
        this.agentToolCallService = agentToolCallService;
        this.agentTaskWorkspaceService = agentTaskWorkspaceService;
        this.agentSafeWorkspaceToolService = agentSafeWorkspaceToolService;
        this.agentArtifactService = agentArtifactService;
    }

    public ToolExecutionResult execute(AgentTask task, AgentTaskStep step) {
        String requestPayload = buildRequestPayload(task, step);
        AgentToolCall toolCall = agentToolCallService.createToolCall(
                task.getId(), step.getId(), step.getToolName(), step.getToolCategory(), step.getRiskLevel(), requestPayload
        );
        try {
            String responsePayload = switch (step.getToolName()) {
                case "task_context_reader" -> readTaskContext(task);
                case "terminal_command" -> agentSafeWorkspaceToolService.executeTerminalCommand(
                        agentTaskWorkspaceService.getWorkspaceDir(task), "dir");
                case "file_writer" -> writeWorkspaceDraft(task, step);
                case "resource_downloader" -> downloadWorkspaceSample(task, step);
                case "final_response_writer" -> writeFinalDeliverable(task, step);
                default -> throw new IllegalArgumentException("Unsupported execution tool: " + step.getToolName());
            };
            agentToolCallService.completeToolCall(toolCall, responsePayload, true, null);
            return new ToolExecutionResult(responsePayload, true, null, LocalDateTime.now());
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            agentToolCallService.completeToolCall(toolCall, null, false, errorMessage);
            return new ToolExecutionResult(null, false, errorMessage, LocalDateTime.now());
        }
    }

    private String buildRequestPayload(AgentTask task, AgentTaskStep step) {
        File workspaceDir = agentTaskWorkspaceService.getWorkspaceDir(task);
        StringBuilder payload = new StringBuilder("Goal: " + task.getGoal()
                + "; Step: " + step.getStepTitle()
                + "; Tool: " + step.getToolName()
                + "; Workspace: " + workspaceDir.getAbsolutePath());
        if (task.getInheritedContextSummary() != null && !task.getInheritedContextSummary().isBlank()) {
            payload.append("; InheritedContext: ").append(task.getInheritedContextSummary());
        }
        return payload.toString();
    }

    private String readTaskContext(AgentTask task) {
        StringBuilder context = new StringBuilder("Workspace ready at " + agentTaskWorkspaceService.getWorkspaceDir(task).getAbsolutePath()
                + ". Task goal: " + task.getGoal());
        if (task.getInheritedContextSummary() != null && !task.getInheritedContextSummary().isBlank()) {
            context.append(System.lineSeparator())
                    .append("Inherited context loaded: ")
                    .append(task.getInheritedContextSummary());
        }
        return context.toString();
    }

    private String writeWorkspaceDraft(AgentTask task, AgentTaskStep step) {
        File artifactFile = agentTaskWorkspaceService.resolveWorkspaceFile(task, "artifacts/draft.txt");
        StringBuilder content = new StringBuilder("Draft artifact for task " + task.getTaskNo()
                + System.lineSeparator() + task.getGoal());
        if (task.getInheritedContextSummary() != null && !task.getInheritedContextSummary().isBlank()) {
            content.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("Inherited context")
                    .append(System.lineSeparator())
                    .append(task.getInheritedContextSummary());
        }
        String result = agentSafeWorkspaceToolService.writeFile(
                agentTaskWorkspaceService.getWorkspaceDir(task), "artifacts/draft.txt", content.toString()
        );
        agentArtifactService.upsertArtifact(
                task.getId(),
                step.getId(),
                "DRAFT",
                "draft.txt",
                "artifacts/draft.txt",
                "text/plain",
                "Draft artifact created during remediation or writing step.",
                artifactFile
        );
        return result;
    }

    private String downloadWorkspaceSample(AgentTask task, AgentTaskStep step) {
        File artifactFile = agentTaskWorkspaceService.resolveWorkspaceFile(task, "downloads/sample.txt");
        String result = agentSafeWorkspaceToolService.downloadFile(
                agentTaskWorkspaceService.getWorkspaceDir(task), "downloads/sample.txt", "https://example.com"
        );
        agentArtifactService.upsertArtifact(
                task.getId(),
                step.getId(),
                "DOWNLOAD",
                "sample.txt",
                "downloads/sample.txt",
                "text/plain",
                "Downloaded workspace resource.",
                artifactFile
        );
        return result;
    }

    private String writeFinalDeliverable(AgentTask task, AgentTaskStep step) {
        File artifactFile = agentTaskWorkspaceService.resolveWorkspaceFile(task, "artifacts/final-result.md");
        String incrementalDeliveryNote = buildIncrementalDeliveryNote(task);
        String content = "# Task Result" + System.lineSeparator()
                + System.lineSeparator()
                + "- Task: " + task.getTitle() + System.lineSeparator()
                + "- Goal: " + task.getGoal() + System.lineSeparator()
                + "- Workspace: " + agentTaskWorkspaceService.getWorkspaceDir(task).getAbsolutePath() + System.lineSeparator()
                + (incrementalDeliveryNote == null ? "" : "- Incremental Delivery: " + incrementalDeliveryNote + System.lineSeparator())
                + (task.getInheritedContextSummary() == null || task.getInheritedContextSummary().isBlank()
                ? ""
                : "- Inherited Context: " + task.getInheritedContextSummary().replace(System.lineSeparator(), " | ")
                + System.lineSeparator());
        String result = agentSafeWorkspaceToolService.writeFile(
                agentTaskWorkspaceService.getWorkspaceDir(task), "artifacts/final-result.md", content
        );
        agentArtifactService.upsertArtifact(
                task.getId(),
                step.getId(),
                "DELIVERABLE",
                "final-result.md",
                "artifacts/final-result.md",
                "text/markdown",
                "Final deliverable generated for the task.",
                artifactFile
        );
        return result;
    }

    private String buildIncrementalDeliveryNote(AgentTask task) {
        if (task.getSourceTaskId() == null) {
            return null;
        }
        String relationLabel = switch (task.getSourceTaskRelation()) {
            case "DUPLICATED_FROM" -> "reruns and refines the previous task";
            case "FOLLOWED_UP_FROM" -> "extends the previous task with a new follow-up result";
            default -> "continues the previous task";
        };
        return "This deliverable " + relationLabel + " from "
                + (task.getSourceTaskNo() == null ? ("task #" + task.getSourceTaskId()) : task.getSourceTaskNo())
                + (task.getSourceTaskTitle() == null ? "" : " (" + task.getSourceTaskTitle() + ")") + ".";
    }

    public record ToolExecutionResult(String output, boolean success, String errorMessage, LocalDateTime finishedAt) {
    }
}
