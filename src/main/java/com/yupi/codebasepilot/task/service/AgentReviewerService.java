package com.yupi.codebasepilot.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.codebasepilot.task.dto.AgentArtifactDto;
import com.yupi.codebasepilot.task.entity.AgentTask;
import com.yupi.codebasepilot.task.entity.AgentTaskStep;
import com.yupi.codebasepilot.task.enums.AgentTaskStepStatus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AgentReviewerService {

    private static final int MAX_OUTPUT_PREVIEW_LENGTH = 800;

    private final ObjectMapper objectMapper;
    private final AgentArtifactService agentArtifactService;

    @Autowired(required = false)
    @Qualifier("dashscopeChatModel")
    private ChatModel dashscopeChatModel;

    public AgentReviewerService(ObjectMapper objectMapper,
                                AgentArtifactService agentArtifactService) {
        this.objectMapper = objectMapper;
        this.agentArtifactService = agentArtifactService;
    }

    public ReviewResult reviewTask(AgentTask task, List<AgentTaskStep> steps) {
        List<AgentArtifactDto> artifacts = agentArtifactService.listArtifacts(task.getId());
        ReadinessCheck readinessCheck = assessTaskReadiness(task, steps, artifacts);
        if (!readinessCheck.ready()) {
            return new ReviewResult(
                    false,
                    readinessCheck.summary(),
                    readinessCheck.detail(),
                    readinessCheck.suggestedAction(),
                    readinessCheck.suggestedStepSeq()
            );
        }
        ReviewResult llmReviewResult = reviewWithModel(task, steps, artifacts);
        if (llmReviewResult != null) {
            return llmReviewResult;
        }
        return reviewWithRules(task, steps, artifacts);
    }

    private ReviewResult reviewWithModel(AgentTask task, List<AgentTaskStep> steps, List<AgentArtifactDto> artifacts) {
        if (dashscopeChatModel == null) {
            return null;
        }
        try {
            ChatClient chatClient = ChatClient.builder(dashscopeChatModel).build();
            String content = chatClient
                    .prompt()
                    .system("""
                            You are a strict task reviewer for an AI agent runtime.
                            Review whether the task result is complete enough for the user's goal.
                            You must respond with JSON only.
                            JSON schema:
                            {
                              "approved": true or false,
                              "summary": "short review conclusion",
                              "finalResult": "if approved, summarize the accepted deliverable; if rejected, explain what is missing and what to do next",
                              "suggestedAction": "one of RETRY_STEP, RESUME_TASK, REPLAN_TASK, STRENGTHEN_HANDOFF, MANUAL_FIX, NONE",
                              "suggestedStepSeq": 1
                            }
                            Keep the review grounded in the supplied task goal, step outputs, and artifact inventory.
                            A task should not be approved if it does not produce a meaningful deliverable artifact.
                            """)
                    .user(buildReviewPrompt(task, steps, artifacts))
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                return null;
            }
            ReviewPayload payload = objectMapper.readValue(extractJson(content), ReviewPayload.class);
            if (payload.summary == null || payload.summary.isBlank() || payload.finalResult == null || payload.finalResult.isBlank()) {
                return null;
            }
            return new ReviewResult(
                    payload.approved,
                    payload.summary,
                    payload.finalResult,
                    normalizeSuggestedAction(payload.suggestedAction),
                    payload.suggestedStepSeq
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    public ReadinessCheck assessTaskReadiness(AgentTask task, List<AgentTaskStep> steps) {
        return assessTaskReadiness(task, steps, agentArtifactService.listArtifacts(task.getId()));
    }

    private ReadinessCheck assessTaskReadiness(AgentTask task, List<AgentTaskStep> steps, List<AgentArtifactDto> artifacts) {
        if (steps == null || steps.isEmpty()) {
            return new ReadinessCheck(false,
                    "Task cannot be completed because no execution steps were generated.",
                    "Fallback reviewer: generate or regenerate a plan before trying to complete this task.",
                    "REPLAN_TASK",
                    null);
        }

        List<AgentTaskStep> failedSteps = steps.stream()
                .filter(step -> step.getStatus() == AgentTaskStepStatus.FAILED)
                .toList();
        if (!failedSteps.isEmpty()) {
            AgentTaskStep failedStep = failedSteps.get(0);
            return new ReadinessCheck(false,
                    "Review blocked because step " + failedStep.getStepSeq() + " failed.",
                    "Fallback reviewer: retry the failed step or adjust the plan before continuing.",
                    "RETRY_STEP",
                    failedStep.getStepSeq());
        }

        AgentTaskStep lastStep = steps.get(steps.size() - 1);
        if (lastStep.getStatus() != AgentTaskStepStatus.SUCCEEDED) {
            return new ReadinessCheck(false,
                    "Review blocked because the final step has not finished successfully.",
                    "Fallback reviewer: resume the task or retry the unfinished step.",
                    "RESUME_TASK",
                    lastStep.getStepSeq());
        }

        String finalOutput = lastStep.getExecutorOutput();
        if (finalOutput == null || finalOutput.isBlank()) {
            return new ReadinessCheck(false,
                    "Review blocked because the final step did not produce a usable output.",
                    "Fallback reviewer: retry the final step or provide a stronger final deliverable instruction.",
                    "RETRY_STEP",
                    lastStep.getStepSeq());
        }

        boolean allStepsSucceeded = steps.stream()
                .allMatch(step -> step.getStatus() == AgentTaskStepStatus.SUCCEEDED);
        if (!allStepsSucceeded) {
            return new ReadinessCheck(false,
                    "Review blocked because some steps are still unfinished.",
                    "Fallback reviewer: resume the task so the remaining steps can run.",
                    "RESUME_TASK",
                    null);
        }

        List<AgentArtifactDto> deliverableArtifacts = artifacts.stream()
                .filter(this::isDeliverableArtifact)
                .toList();
        if (artifacts.isEmpty()) {
            return new ReadinessCheck(false,
                    "Review blocked because the task produced no artifact for the user to inspect.",
                    "Fallback reviewer: rerun the final writing step so the task leaves behind a concrete deliverable artifact.",
                    "RETRY_STEP",
                    lastStep.getStepSeq());
        }
        if (deliverableArtifacts.isEmpty()) {
            return new ReadinessCheck(false,
                    "Review blocked because no final deliverable artifact was generated.",
                    "Fallback reviewer: rerun or revise the final response step so the workspace contains a deliverable file.",
                    "RETRY_STEP",
                    lastStep.getStepSeq());
        }

        String handoffContext = resolveHandoffContext(task, steps, artifacts);
        if (!isHandoffContextReady(task, steps, artifacts, handoffContext)) {
            return new ReadinessCheck(false,
                    "Review blocked because the task is not ready to hand off its result to a follow-up task.",
                    "Fallback reviewer: strengthen the final output and handoff context so the task leaves behind a clearer summary, review signal, and deliverable context for the next task.",
                    "STRENGTHEN_HANDOFF",
                    lastStep.getStepSeq());
        }

        return new ReadinessCheck(
                true,
                "Task is structurally ready for review.",
                "Readiness gate passed. Steps, deliverables, and handoff context are present for final review.",
                "NONE",
                null
        );
    }

    private ReviewResult reviewWithRules(AgentTask task, List<AgentTaskStep> steps, List<AgentArtifactDto> artifacts) {
        AgentTaskStep lastStep = steps.get(steps.size() - 1);
        String finalOutput = defaultText(lastStep.getExecutorOutput());
        List<AgentArtifactDto> deliverableArtifacts = artifacts.stream()
                .filter(this::isDeliverableArtifact)
                .toList();
        String summary = "Fallback reviewer accepted the task result for goal: " + task.getGoal();
        String finalResult = "Task passed fallback review. Final deliverable looks complete enough for the current runtime."
                + System.lineSeparator()
                + "Accepted artifacts: " + describeArtifacts(deliverableArtifacts)
                + System.lineSeparator()
                + "Handoff context is ready for a follow-up task."
                + System.lineSeparator()
                + finalOutput;
        return new ReviewResult(true, summary, finalResult, "NONE", null);
    }

    private String normalizeSuggestedAction(String suggestedAction) {
        if (suggestedAction == null || suggestedAction.isBlank()) {
            return "NONE";
        }
        return switch (suggestedAction.trim().toUpperCase()) {
            case "RETRY_STEP", "RESUME_TASK", "REPLAN_TASK", "STRENGTHEN_HANDOFF", "MANUAL_FIX", "NONE" -> suggestedAction.trim().toUpperCase();
            default -> "NONE";
        };
    }

    private String buildReviewPrompt(AgentTask task, List<AgentTaskStep> steps, List<AgentArtifactDto> artifacts) {
        String stepSummary = steps.stream()
                .map(step -> """
                        Step %s
                        - title: %s
                        - status: %s
                        - tool: %s
                        - output: %s
                        """.formatted(
                        step.getStepSeq(),
                        step.getStepTitle(),
                        step.getStatus(),
                        step.getToolName(),
                        truncate(step.getExecutorOutput())
                ))
                .collect(Collectors.joining(System.lineSeparator()));
        String artifactSummary = artifacts.isEmpty()
                ? "(empty)"
                : artifacts.stream()
                .map(artifact -> """
                        - %s [%s]
                          path: %s
                          type: %s
                          size: %s bytes
                        """.formatted(
                        artifact.getArtifactName(),
                        artifact.getArtifactType(),
                        artifact.getRelativePath(),
                        defaultText(artifact.getContentType()),
                        artifact.getSizeBytes() == null ? 0 : artifact.getSizeBytes()
                ))
                .collect(Collectors.joining(System.lineSeparator()));
        String handoffContext = resolveHandoffContext(task, steps, artifacts);
        return """
                Task goal:
                %s

                Inherited context:
                %s

                Handoff context for the next task:
                %s

                Task type:
                %s

                Plan summary:
                %s

                Current final result:
                %s

                Artifact summary:
                %s

                Step execution summary:
                %s
        """.formatted(
                task.getGoal(),
                defaultText(task.getInheritedContextSummary()),
                handoffContext,
                task.getTaskType(),
                defaultText(task.getPlanSummary()),
                defaultText(task.getFinalResult()),
                artifactSummary,
                stepSummary
        );
    }

    private boolean isHandoffContextReady(AgentTask task,
                                          List<AgentTaskStep> steps,
                                          List<AgentArtifactDto> artifacts,
                                          String handoffContext) {
        if (handoffContext == null || handoffContext.isBlank() || handoffContext.length() < 160) {
            return false;
        }
        boolean hasSummarySignal = !defaultText(task.getTaskSummary()).equals("(empty)")
                || !defaultText(task.getReviewSummary()).equals("(empty)")
                || !defaultText(task.getFinalResult()).equals("(empty)");
        boolean hasUsefulOutput = steps.stream()
                .map(AgentTaskStep::getExecutorOutput)
                .anyMatch(output -> output != null && output.trim().length() >= 80);
        boolean hasDeliverable = artifacts.stream().anyMatch(this::isDeliverableArtifact);
        return hasDeliverable && (hasSummarySignal || hasUsefulOutput);
    }

    private String resolveHandoffContext(AgentTask task, List<AgentTaskStep> steps, List<AgentArtifactDto> artifacts) {
        if (task.getHandoffContextSummary() != null && !task.getHandoffContextSummary().isBlank()) {
            return task.getHandoffContextSummary();
        }
        String artifactSummary = artifacts.stream()
                .map(AgentArtifactDto::getArtifactName)
                .filter(name -> name != null && !name.isBlank())
                .limit(5)
                .collect(Collectors.joining(", "));
        String stepOutputSummary = steps.stream()
                .map(AgentTaskStep::getExecutorOutput)
                .filter(output -> output != null && !output.isBlank())
                .map(this::truncate)
                .findFirst()
                .orElse("(empty)");
        return """
                Task: %s
                Goal: %s
                Summary: %s
                Review: %s
                Final result: %s
                Artifacts: %s
                Step output: %s
                """.formatted(
                defaultText(task.getTitle()),
                defaultText(task.getGoal()),
                defaultText(task.getTaskSummary()),
                defaultText(task.getReviewSummary()),
                defaultText(task.getFinalResult()),
                artifactSummary.isBlank() ? "(empty)" : artifactSummary,
                stepOutputSummary
        ).trim();
    }

    private boolean isDeliverableArtifact(AgentArtifactDto artifact) {
        return artifact != null
                && ("DELIVERABLE".equalsIgnoreCase(artifact.getArtifactType())
                || "text/markdown".equalsIgnoreCase(artifact.getContentType())
                || artifact.getArtifactName().toLowerCase().endsWith(".md"));
    }

    private String describeArtifacts(List<AgentArtifactDto> artifacts) {
        return artifacts.stream()
                .map(artifact -> artifact.getArtifactName() + " (" + artifact.getRelativePath() + ")")
                .collect(Collectors.joining(", "));
    }

    private String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        if (text.length() <= MAX_OUTPUT_PREVIEW_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_PREVIEW_LENGTH) + "...";
    }

    private String defaultText(String text) {
        return text == null || text.isBlank() ? "(empty)" : text;
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private static class ReviewPayload {
        public boolean approved;
        public String summary;
        public String finalResult;
        public String suggestedAction;
        public Integer suggestedStepSeq;
    }

    public record ReviewResult(boolean approved,
                               String summary,
                               String finalResult,
                               String suggestedAction,
                               Integer suggestedStepSeq) {
    }

    public record ReadinessCheck(boolean ready,
                                 String summary,
                                 String detail,
                                 String suggestedAction,
                                 Integer suggestedStepSeq) {
    }
}
