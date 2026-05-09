package com.yupi.codebasepilot.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.codebasepilot.task.dto.AgentPlanDiffItemDto;
import com.yupi.codebasepilot.task.dto.AgentPlanSnapshotStepDto;
import com.yupi.codebasepilot.task.entity.AgentTask;
import com.yupi.codebasepilot.task.entity.AgentApproval;
import com.yupi.codebasepilot.task.entity.AgentTaskStep;
import com.yupi.codebasepilot.task.service.AgentPlannerService.PlanResult;
import com.yupi.codebasepilot.task.service.AgentPlannerService.PlannedStep;
import com.yupi.codebasepilot.task.service.AgentReviewerService.ReadinessCheck;
import com.yupi.codebasepilot.task.service.AgentReviewerService.ReviewResult;
import com.yupi.codebasepilot.task.enums.AgentApprovalStatus;
import com.yupi.codebasepilot.task.enums.AgentTaskBusinessType;
import com.yupi.codebasepilot.task.enums.AgentTaskStatus;
import com.yupi.codebasepilot.task.enums.AgentTaskStepStatus;
import com.yupi.codebasepilot.task.repository.AgentApprovalRepository;
import com.yupi.codebasepilot.task.repository.AgentTaskRepository;
import com.yupi.codebasepilot.task.repository.AgentTaskStepRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class AgentTaskRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskRuntimeService.class);

    private final AgentTaskRepository agentTaskRepository;
    private final AgentTaskStepRepository agentTaskStepRepository;
    private final AgentApprovalRepository agentApprovalRepository;
    private final AgentTaskEventService agentTaskEventService;
    private final AgentPlannerService agentPlannerService;
    private final AgentWorkerService agentWorkerService;
    private final AgentReviewerService agentReviewerService;
    private final CodebaseAgentOrchestratorService codebaseAgentOrchestratorService;
    private final ObjectMapper objectMapper;

    public AgentTaskRuntimeService(AgentTaskRepository agentTaskRepository,
                                   AgentTaskStepRepository agentTaskStepRepository,
                                   AgentApprovalRepository agentApprovalRepository,
                                   AgentTaskEventService agentTaskEventService,
                                   AgentPlannerService agentPlannerService,
                                   AgentWorkerService agentWorkerService,
                                   AgentReviewerService agentReviewerService,
                                   CodebaseAgentOrchestratorService codebaseAgentOrchestratorService,
                                   ObjectMapper objectMapper) {
        this.agentTaskRepository = agentTaskRepository;
        this.agentTaskStepRepository = agentTaskStepRepository;
        this.agentApprovalRepository = agentApprovalRepository;
        this.agentTaskEventService = agentTaskEventService;
        this.agentPlannerService = agentPlannerService;
        this.agentWorkerService = agentWorkerService;
        this.agentReviewerService = agentReviewerService;
        this.codebaseAgentOrchestratorService = codebaseAgentOrchestratorService;
        this.objectMapper = objectMapper;
    }

    public void startRuntime(Long taskId) {
        CompletableFuture.runAsync(() -> {
            try {
                if (isCodebaseOrchestratedTask(taskId)) {
                    codebaseAgentOrchestratorService.runTask(taskId);
                    return;
                }
                runTask(taskId);
            } catch (Exception e) {
                log.error("Failed to run task runtime for task {}", taskId, e);
                handleAsyncRuntimeFailure(taskId, e);
            }
        });
    }

    @Transactional
    public void handleAsyncRuntimeFailure(Long taskId, Exception exception) {
        AgentTask task = agentTaskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("Skip runtime failure handling because task {} no longer exists.", taskId);
            return;
        }
        if (task.getStatus() == AgentTaskStatus.SUCCEEDED
                || task.getStatus() == AgentTaskStatus.CANCELLED) {
            return;
        }
        String message = "Unhandled runtime error: " + exception.getMessage();
        task.setStatus(AgentTaskStatus.FAILED);
        task.setErrorMessage(message);
        task.setFinishedAt(LocalDateTime.now());

        AgentTaskStep affectedStep = agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(taskId).stream()
                .filter(step -> step.getStatus() == AgentTaskStepStatus.RUNNING || step.getStatus() == AgentTaskStepStatus.PENDING)
                .findFirst()
                .orElse(null);
        if (affectedStep != null) {
            affectedStep.setStatus(AgentTaskStepStatus.FAILED);
            if (affectedStep.getStartedAt() == null) {
                affectedStep.setStartedAt(LocalDateTime.now());
            }
            affectedStep.setFinishedAt(LocalDateTime.now());
            if (affectedStep.getExecutorOutput() == null) {
                affectedStep.setExecutorOutput(message);
            }
            agentTaskStepRepository.save(affectedStep);
        }
        agentTaskRepository.save(task);
        try {
            agentTaskEventService.appendEvent(taskId, affectedStep == null ? null : affectedStep.getId(),
                    "TASK_RUNTIME_FAILED", "ERROR", message, null);
        } catch (Exception eventException) {
            log.error("Failed to append runtime failure event for task {}", taskId, eventException);
        }
    }

    @Transactional
    public void replanTask(Long taskId) {
        if (isCodebaseOrchestratedTask(taskId)) {
            codebaseAgentOrchestratorService.manualReplanTask(taskId);
            return;
        }
        AgentTask task = findTask(taskId);
        List<AgentTaskStep> existingSteps = agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(taskId);
        PlanResult planResult = agentPlannerService.createReplan(task);
        List<AgentPlanSnapshotStepDto> previousSteps = toSnapshotSteps(existingSteps);
        List<AgentPlanSnapshotStepDto> replannedSteps = toPlannedSnapshotSteps(planResult.getSteps());
        List<AgentPlanDiffItemDto> diffItems = buildPlanDiff(previousSteps, replannedSteps);
        agentApprovalRepository.deleteByTaskId(taskId);
        agentTaskStepRepository.deleteByTaskId(taskId);
        task.setPreviousPlanSummary(task.getPlanSummary());
        task.setPreviousPlanStepsSnapshot(writeJson(previousSteps));
        task.setPlanSummary(planResult.getSummary());
        task.setPlanDiffSummary(buildPlanDiffSummary(previousSteps, replannedSteps, diffItems));
        task.setPlanDiffSnapshot(writeJson(diffItems));
        task.setStatus(AgentTaskStatus.WAITING_PLAN_CONFIRMATION);
        task.setCurrentStepSeq(null);
        task.setFinalResult(null);
        task.setErrorMessage(null);
        task.setReviewSummary(null);
        task.setReviewSuggestedAction(null);
        task.setReviewSuggestedStepSeq(null);
        task.setFinishedAt(null);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, null, "TASK_REPLANNED", "INFO",
                "Task plan has been regenerated after reviewer feedback.", task.getPlanDiffSummary());
        for (PlannedStep plannedStep : planResult.getSteps()) {
            createStep(task, plannedStep);
        }
        agentTaskEventService.appendEvent(taskId, null, "PLAN_CONFIRMATION_REQUIRED", "WARN",
                "A revised plan is ready. Please confirm the new plan before execution continues.", task.getPlanSummary());
    }

    @Transactional
    public void ensurePlan(Long taskId) {
        if (isCodebaseOrchestratedTask(taskId)) {
            codebaseAgentOrchestratorService.ensureInitialPlan(taskId);
            return;
        }
        AgentTask task = findTask(taskId);
        List<AgentTaskStep> existingSteps = agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(taskId);
        if (!existingSteps.isEmpty()) {
            return;
        }

        PlanResult planResult = agentPlannerService.createInitialPlan(task);
        task.setPlanSummary(planResult.getSummary());
        task.setStatus(AgentTaskStatus.PLANNING);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, null, "PLAN_CREATED", "INFO",
                "Initial execution plan has been generated.", task.getPlanSummary());

        for (PlannedStep plannedStep : planResult.getSteps()) {
            createStep(task, plannedStep);
        }

        task.setStatus(AgentTaskStatus.RUNNING);
        agentTaskRepository.save(task);
    }

    @Transactional
    public void runTask(Long taskId) {
        if (isCodebaseOrchestratedTask(taskId)) {
            codebaseAgentOrchestratorService.runTask(taskId);
            return;
        }
        ensurePlan(taskId);
        AgentTask task = findTask(taskId);
        List<AgentTaskStep> steps = agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(taskId);

        for (AgentTaskStep step : steps) {
            task = findTask(taskId);
            if (task.getStatus() == AgentTaskStatus.CANCELLED) {
                agentTaskEventService.appendEvent(taskId, step.getId(), "TASK_CANCELLED", "WARN",
                        "Task execution stopped because the task was cancelled.", null);
                return;
            }
            if (task.getStatus() == AgentTaskStatus.FAILED) {
                agentTaskEventService.appendEvent(taskId, step.getId(), "TASK_FAILED", "ERROR",
                        "Task execution stopped because a previous step failed.", task.getErrorMessage());
                return;
            }
            if (task.getStatus() == AgentTaskStatus.BLOCKED) {
                agentTaskEventService.appendEvent(taskId, step.getId(), "TASK_BLOCKED", "WARN",
                        "Task execution is blocked and waiting for user action.", null);
                return;
            }
            if (step.getStatus() == AgentTaskStepStatus.SUCCEEDED) {
                continue;
            }
            if (waitForApprovalIfNeeded(task, step)) {
                return;
            }
            executeStep(task, step);
            if (findTask(taskId).getStatus() == AgentTaskStatus.FAILED) {
                return;
            }
        }

        task = findTask(taskId);
        if (task.getStatus() == AgentTaskStatus.CANCELLED
                || task.getStatus() == AgentTaskStatus.BLOCKED
                || task.getStatus() == AgentTaskStatus.WAITING_PLAN_CONFIRMATION
                || task.getStatus() == AgentTaskStatus.WAITING_APPROVAL
                || task.getStatus() == AgentTaskStatus.FAILED) {
            return;
        }
        ReadinessCheck readinessCheck = agentReviewerService.assessTaskReadiness(task, steps);
        if (!readinessCheck.ready()) {
            String eventType = "TASK_COMPLETION_BLOCKED";
            String eventContent = "Execution finished, but the task is not ready for final review.";
            if ("STRENGTHEN_HANDOFF".equals(readinessCheck.suggestedAction())) {
                eventType = "TASK_HANDOFF_BLOCKED";
                eventContent = "Execution finished, but the handoff context is not strong enough for a follow-up task yet.";
            }
            blockTaskBeforeReview(task, readinessCheck, eventType, eventContent);
            return;
        }
        task.setStatus(AgentTaskStatus.REVIEWING);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, null, "TASK_REVIEW_STARTED", "INFO",
                "Execution finished. Reviewer is checking whether the result is complete.", null);
        finishReview(taskId);
    }

    @Transactional
    public void finishReview(Long taskId) {
        if (isCodebaseOrchestratedTask(taskId)) {
            codebaseAgentOrchestratorService.runTask(taskId);
            return;
        }
        AgentTask task = findTask(taskId);
        List<AgentTaskStep> steps = agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(taskId);
        ReadinessCheck readinessCheck = agentReviewerService.assessTaskReadiness(task, steps);
        if (!readinessCheck.ready()) {
            String eventType = "TASK_REVIEW_BLOCKED";
            String eventContent = "Review was requested, but the task is still missing required execution outputs or deliverables.";
            if ("STRENGTHEN_HANDOFF".equals(readinessCheck.suggestedAction())) {
                eventType = "TASK_HANDOFF_BLOCKED";
                eventContent = "Review was requested, but the task still needs a stronger handoff package before it can be treated as complete.";
            }
            blockTaskBeforeReview(task, readinessCheck, eventType, eventContent);
            return;
        }
        ReviewResult reviewResult = agentReviewerService.reviewTask(task, steps);
        if (reviewResult.approved()) {
            task.setStatus(AgentTaskStatus.SUCCEEDED);
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(null);
            task.setReviewSummary(reviewResult.summary());
            task.setReviewSuggestedAction(reviewResult.suggestedAction());
            task.setReviewSuggestedStepSeq(reviewResult.suggestedStepSeq());
            task.setFinalResult(reviewResult.finalResult());
            agentTaskRepository.save(task);
            agentTaskEventService.appendEvent(taskId, null, "TASK_COMPLETED", "INFO",
                    reviewResult.summary(), reviewResult.finalResult());
            return;
        }
        task.setStatus(AgentTaskStatus.BLOCKED);
        task.setErrorMessage(reviewResult.summary());
        task.setReviewSummary(reviewResult.summary());
        task.setReviewSuggestedAction(reviewResult.suggestedAction());
        task.setReviewSuggestedStepSeq(reviewResult.suggestedStepSeq());
        task.setFinalResult(reviewResult.finalResult());
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, null, "TASK_REVIEW_BLOCKED", "WARN",
                reviewResult.summary(), reviewResult.finalResult());
    }

    private void blockTaskBeforeReview(AgentTask task,
                                       ReadinessCheck readinessCheck,
                                       String eventType,
                                       String eventContent) {
        task.setStatus(AgentTaskStatus.BLOCKED);
        task.setErrorMessage(readinessCheck.summary());
        task.setReviewSummary(readinessCheck.summary());
        task.setReviewSuggestedAction(readinessCheck.suggestedAction());
        task.setReviewSuggestedStepSeq(readinessCheck.suggestedStepSeq());
        task.setFinalResult(readinessCheck.detail());
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(task.getId(), null, eventType, "WARN", eventContent, readinessCheck.detail());
    }

    @Transactional
    public void executeStep(AgentTask task, AgentTaskStep step) {
        step.setStatus(AgentTaskStepStatus.RUNNING);
        step.setStartedAt(LocalDateTime.now());
        task.setCurrentStepSeq(step.getStepSeq());
        agentTaskRepository.save(task);
        agentTaskStepRepository.save(step);
        agentTaskEventService.appendEvent(task.getId(), step.getId(), "STEP_STARTED", "INFO",
                "Started step " + step.getStepSeq() + ": " + step.getStepTitle(), null);

        step.setExecutorInput(buildExecutorInput(task, step));
        AgentWorkerService.WorkerResult workerResult = agentWorkerService.executeStep(
                task, step, step.getToolName(), step.getToolCategory(), step.getRiskLevel()
        );
        if (!workerResult.success()) {
            step.setExecutorOutput(workerResult.errorMessage());
            step.setStatus(AgentTaskStepStatus.FAILED);
            step.setFinishedAt(LocalDateTime.now());
            task.setStatus(AgentTaskStatus.FAILED);
            task.setErrorMessage(workerResult.errorMessage());
            agentTaskRepository.save(task);
            agentTaskStepRepository.save(step);
            agentTaskEventService.appendEvent(task.getId(), step.getId(), "STEP_FAILED", "ERROR",
                    "Step failed while executing tool " + step.getToolName(), workerResult.errorMessage());
            return;
        }
        step.setExecutorOutput(workerResult.output());
        step.setStatus(AgentTaskStepStatus.SUCCEEDED);
        step.setFinishedAt(LocalDateTime.now());
        agentTaskStepRepository.save(step);
        agentTaskEventService.appendEvent(task.getId(), step.getId(), "STEP_COMPLETED", "INFO",
                step.getExecutorOutput(), null);
    }

    private String buildExecutorInput(AgentTask task, AgentTaskStep step) {
        StringBuilder input = new StringBuilder();
        input.append("Goal: ").append(task.getGoal())
                .append("; Tool: ").append(step.getToolName())
                .append("; Step: ").append(step.getStepTitle());
        if (task.getInheritedContextSummary() != null && !task.getInheritedContextSummary().isBlank()) {
            input.append("; InheritedContext: ").append(task.getInheritedContextSummary());
        }
        return input.toString();
    }

    private AgentTaskStep createStep(AgentTask task, PlannedStep plannedStep) {
        AgentTaskStep step = new AgentTaskStep();
        step.setTaskId(task.getId());
        step.setStepSeq(plannedStep.getStepSeq());
        step.setStepTitle(plannedStep.getTitle());
        step.setStepType(plannedStep.getStepType());
        step.setAssignedAgent("LegacyWorker");
        step.setToolName(plannedStep.getToolName());
        step.setToolCategory(plannedStep.getToolCategory());
        step.setRiskLevel(plannedStep.getRiskLevel());
        step.setStatus(AgentTaskStepStatus.PENDING);
        step.setPlannerOutput("Generated by planner with tool " + plannedStep.getToolName() + ".");
        step.setRetryCount(0);
        step.setMaxRetry(1);
        step.setRequiresApproval(plannedStep.getRequiresApproval());
        AgentTaskStep savedStep = agentTaskStepRepository.save(step);
        agentTaskEventService.appendEvent(task.getId(), savedStep.getId(), "STEP_CREATED", "INFO",
                "Created step " + plannedStep.getStepSeq() + ": " + plannedStep.getTitle(), null);
        if (plannedStep.getRequiresApproval()) {
            createApproval(task, savedStep, plannedStep);
        }
        return savedStep;
    }

    private boolean waitForApprovalIfNeeded(AgentTask task, AgentTaskStep step) {
        if (!Boolean.TRUE.equals(step.getRequiresApproval())) {
            return false;
        }
        AgentApproval rejectedApproval = agentApprovalRepository
                .findFirstByTaskIdAndStepIdAndStatusOrderByCreatedAtDesc(task.getId(), step.getId(), AgentApprovalStatus.REJECTED)
                .orElse(null);
        if (rejectedApproval != null) {
            task.setStatus(AgentTaskStatus.BLOCKED);
            task.setErrorMessage("Task is blocked because an approval request was rejected.");
            agentTaskRepository.save(task);
            return true;
        }

        AgentApproval pendingApproval = agentApprovalRepository
                .findFirstByTaskIdAndStepIdAndStatusOrderByCreatedAtDesc(task.getId(), step.getId(), AgentApprovalStatus.PENDING)
                .orElse(null);
        if (pendingApproval == null) {
            return false;
        }
        task.setStatus(AgentTaskStatus.WAITING_APPROVAL);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(task.getId(), step.getId(), "APPROVAL_REQUIRED", "WARN",
                pendingApproval.getTitle(), pendingApproval.getReason());
        return true;
    }

    private void createApproval(AgentTask task, AgentTaskStep step, PlannedStep plannedStep) {
        AgentApproval approval = new AgentApproval();
        approval.setTaskId(task.getId());
        approval.setStepId(step.getId());
        approval.setApprovalType("TOOL_EXECUTION");
        approval.setTitle("Approval required before using tool " + step.getToolName());
        approval.setReason("Tool category: " + step.getToolCategory() + ", risk level: " + step.getRiskLevel());
        approval.setPayload(step.getStepTitle() + " | tool=" + plannedStep.getToolName());
        approval.setStatus(AgentApprovalStatus.PENDING);
        agentApprovalRepository.save(approval);
        agentTaskEventService.appendEvent(task.getId(), step.getId(), "APPROVAL_CREATED", "WARN",
                approval.getTitle(), approval.getReason());
    }

    private AgentTask findTask(Long taskId) {
        return agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task not found: " + taskId));
    }

    private boolean isCodebaseOrchestratedTask(Long taskId) {
        return agentTaskRepository.findById(taskId)
                .map(task -> AgentTaskBusinessType.CODE_UNDERSTANDING.name().equalsIgnoreCase(task.getBusinessType())
                        || AgentTaskBusinessType.BUG_DIAGNOSIS.name().equalsIgnoreCase(task.getBusinessType())
                        || AgentTaskBusinessType.PATCH_SUGGESTION.name().equalsIgnoreCase(task.getBusinessType()))
                .orElse(false);
    }

    private List<AgentPlanSnapshotStepDto> toSnapshotSteps(List<AgentTaskStep> steps) {
        return steps.stream()
                .sorted(Comparator.comparing(AgentTaskStep::getStepSeq))
                .map(step -> AgentPlanSnapshotStepDto.builder()
                        .stepSeq(step.getStepSeq())
                        .stepTitle(step.getStepTitle())
                        .stepType(step.getStepType())
                        .toolName(step.getToolName())
                        .toolCategory(step.getToolCategory())
                        .riskLevel(step.getRiskLevel())
                        .build())
                .toList();
    }

    private List<AgentPlanSnapshotStepDto> toPlannedSnapshotSteps(List<PlannedStep> steps) {
        return steps.stream()
                .sorted(Comparator.comparing(PlannedStep::getStepSeq))
                .map(step -> AgentPlanSnapshotStepDto.builder()
                        .stepSeq(step.getStepSeq())
                        .stepTitle(step.getTitle())
                        .stepType(step.getStepType())
                        .toolName(step.getToolName())
                        .toolCategory(step.getToolCategory())
                        .riskLevel(step.getRiskLevel())
                        .build())
                .toList();
    }

    private List<AgentPlanDiffItemDto> buildPlanDiff(List<AgentPlanSnapshotStepDto> previousSteps,
                                                     List<AgentPlanSnapshotStepDto> currentSteps) {
        Map<Integer, AgentPlanSnapshotStepDto> previousBySeq = previousSteps.stream()
                .collect(Collectors.toMap(AgentPlanSnapshotStepDto::getStepSeq, Function.identity(), (left, right) -> right));
        Map<Integer, AgentPlanSnapshotStepDto> currentBySeq = currentSteps.stream()
                .collect(Collectors.toMap(AgentPlanSnapshotStepDto::getStepSeq, Function.identity(), (left, right) -> right));

        List<Integer> allStepSeqs = new ArrayList<>();
        allStepSeqs.addAll(previousBySeq.keySet());
        currentBySeq.keySet().stream()
                .filter(seq -> !allStepSeqs.contains(seq))
                .forEach(allStepSeqs::add);

        return allStepSeqs.stream()
                .sorted()
                .map(stepSeq -> {
                    AgentPlanSnapshotStepDto previous = previousBySeq.get(stepSeq);
                    AgentPlanSnapshotStepDto current = currentBySeq.get(stepSeq);
                    if (previous == null && current != null) {
                        return AgentPlanDiffItemDto.builder()
                                .changeType("ADDED")
                                .stepSeq(stepSeq)
                                .currentLabel(formatStepLabel(current))
                                .reason("New remediation step added in the replanned workflow.")
                                .build();
                    }
                    if (previous != null && current == null) {
                        return AgentPlanDiffItemDto.builder()
                                .changeType("REMOVED")
                                .stepSeq(stepSeq)
                                .previousLabel(formatStepLabel(previous))
                                .reason("Previous step removed from the revised workflow.")
                                .build();
                    }
                    if (previous != null && current != null && hasStepChanged(previous, current)) {
                        return AgentPlanDiffItemDto.builder()
                                .changeType("CHANGED")
                                .stepSeq(stepSeq)
                                .previousLabel(formatStepLabel(previous))
                                .currentLabel(formatStepLabel(current))
                                .reason(buildChangeReason(previous, current))
                                .build();
                    }
                    return AgentPlanDiffItemDto.builder()
                            .changeType("UNCHANGED")
                            .stepSeq(stepSeq)
                            .previousLabel(formatStepLabel(previous))
                            .currentLabel(formatStepLabel(current))
                            .reason("Step kept in the revised plan.")
                            .build();
                })
                .toList();
    }

    private boolean hasStepChanged(AgentPlanSnapshotStepDto previous, AgentPlanSnapshotStepDto current) {
        return !Objects.equals(previous.getStepTitle(), current.getStepTitle())
                || !Objects.equals(previous.getStepType(), current.getStepType())
                || !Objects.equals(previous.getToolName(), current.getToolName())
                || !Objects.equals(previous.getToolCategory(), current.getToolCategory())
                || !Objects.equals(previous.getRiskLevel(), current.getRiskLevel());
    }

    private String buildChangeReason(AgentPlanSnapshotStepDto previous, AgentPlanSnapshotStepDto current) {
        List<String> reasons = new ArrayList<>();
        if (!Objects.equals(previous.getStepTitle(), current.getStepTitle())) {
            reasons.add("step goal updated");
        }
        if (!Objects.equals(previous.getToolName(), current.getToolName())) {
            reasons.add("tool changed");
        }
        if (!Objects.equals(previous.getToolCategory(), current.getToolCategory())) {
            reasons.add("tool category changed");
        }
        if (!Objects.equals(previous.getRiskLevel(), current.getRiskLevel())) {
            reasons.add("risk level changed");
        }
        if (!Objects.equals(previous.getStepType(), current.getStepType())) {
            reasons.add("step type changed");
        }
        return reasons.isEmpty() ? "Step definition was adjusted." : String.join(", ", reasons) + ".";
    }

    private String buildPlanDiffSummary(List<AgentPlanSnapshotStepDto> previousSteps,
                                        List<AgentPlanSnapshotStepDto> currentSteps,
                                        List<AgentPlanDiffItemDto> diffItems) {
        long addedCount = diffItems.stream().filter(item -> "ADDED".equals(item.getChangeType())).count();
        long removedCount = diffItems.stream().filter(item -> "REMOVED".equals(item.getChangeType())).count();
        long changedCount = diffItems.stream().filter(item -> "CHANGED".equals(item.getChangeType())).count();
        return "Previous plan had %d steps, revised plan has %d steps. Added %d, removed %d, changed %d."
                .formatted(previousSteps.size(), currentSteps.size(), addedCount, removedCount, changedCount);
    }

    private String formatStepLabel(AgentPlanSnapshotStepDto step) {
        if (step == null) {
            return null;
        }
        return "Step %d - %s (%s / %s)".formatted(
                step.getStepSeq(),
                step.getStepTitle(),
                step.getStepType(),
                step.getToolName()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize plan diff snapshot.", e);
        }
    }
}
