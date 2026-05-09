package com.yupi.codebasepilot.task.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.codebasepilot.task.dto.AgentApprovalDto;
import com.yupi.codebasepilot.task.dto.AgentArtifactDto;
import com.yupi.codebasepilot.task.dto.AgentPlanDiffItemDto;
import com.yupi.codebasepilot.task.dto.AgentPlanSnapshotStepDto;
import com.yupi.codebasepilot.task.dto.AgentTaskCreateRequest;
import com.yupi.codebasepilot.task.dto.AgentTaskEventDto;
import com.yupi.codebasepilot.task.dto.AgentTaskOverviewResponse;
import com.yupi.codebasepilot.task.dto.AgentTaskResponse;
import com.yupi.codebasepilot.task.dto.AgentTaskStepDto;
import com.yupi.codebasepilot.task.dto.AgentToolCallDto;
import com.yupi.codebasepilot.task.dto.EvidenceRefDto;
import com.yupi.codebasepilot.repo.entity.Repo;
import com.yupi.codebasepilot.repo.repository.RepoRepository;
import com.yupi.codebasepilot.task.entity.AgentTask;
import com.yupi.codebasepilot.task.enums.AgentTaskBusinessType;
import com.yupi.codebasepilot.task.enums.AgentTaskStatus;
import com.yupi.codebasepilot.task.repository.AgentApprovalRepository;
import com.yupi.codebasepilot.task.repository.AgentTaskRepository;
import com.yupi.codebasepilot.task.repository.AgentTaskStepRepository;
import com.yupi.codebasepilot.task.service.AgentTaskSummaryService.TaskSummarySnapshot;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class AgentTaskService {

    private final AgentTaskRepository agentTaskRepository;
    private final AgentTaskStepRepository agentTaskStepRepository;
    private final AgentApprovalRepository agentApprovalRepository;
    private final AgentTaskEventService agentTaskEventService;
    private final AgentTaskRuntimeService agentTaskRuntimeService;
    private final AgentToolCallService agentToolCallService;
    private final AgentTaskWorkspaceService agentTaskWorkspaceService;
    private final AgentArtifactService agentArtifactService;
    private final AgentTaskSummaryService agentTaskSummaryService;
    private final RepoRepository repoRepository;
    private final ObjectMapper objectMapper;

    public AgentTaskService(AgentTaskRepository agentTaskRepository,
                            AgentTaskStepRepository agentTaskStepRepository,
                            AgentApprovalRepository agentApprovalRepository,
                            AgentTaskEventService agentTaskEventService,
                            AgentTaskRuntimeService agentTaskRuntimeService,
                            AgentToolCallService agentToolCallService,
                            AgentTaskWorkspaceService agentTaskWorkspaceService,
                            AgentArtifactService agentArtifactService,
                            AgentTaskSummaryService agentTaskSummaryService,
                            RepoRepository repoRepository,
                            ObjectMapper objectMapper) {
        this.agentTaskRepository = agentTaskRepository;
        this.agentTaskStepRepository = agentTaskStepRepository;
        this.agentApprovalRepository = agentApprovalRepository;
        this.agentTaskEventService = agentTaskEventService;
        this.agentTaskRuntimeService = agentTaskRuntimeService;
        this.agentToolCallService = agentToolCallService;
        this.agentTaskWorkspaceService = agentTaskWorkspaceService;
        this.agentArtifactService = agentArtifactService;
        this.agentTaskSummaryService = agentTaskSummaryService;
        this.repoRepository = repoRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentTaskResponse createTask(AgentTaskCreateRequest request) {
        validateCreateRequest(request);
        AgentTask savedTask = createTaskEntity(request);
        agentTaskEventService.appendEvent(savedTask.getId(), null, "TASK_CREATED", "INFO",
                "Task created and waiting for planning or execution.", null);
        return getTask(savedTask.getId());
    }

    @Transactional
    public AgentTaskResponse duplicateTask(Long taskId) {
        AgentTask sourceTask = findTask(taskId);
        AgentTaskResponse sourceTaskResponse = toResponse(refreshTaskSummary(sourceTask));
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setTitle(buildDuplicateTitle(sourceTask.getTitle()));
        request.setGoal(sourceTask.getGoal());
        request.setTaskType(sourceTask.getTaskType());
        request.setRepoId(sourceTask.getRepoId());
        request.setBusinessType(sourceTask.getBusinessType());
        request.setAutoApproveLowRisk(sourceTask.getAutoApproveLowRisk());
        AgentTask savedTask = createTaskEntity(request);
        attachSourceTask(savedTask, sourceTask, "DUPLICATED_FROM");
        savedTask.setInheritedContextSummary(buildInheritedContextSummary(sourceTaskResponse, "DUPLICATED_FROM"));
        savedTask = agentTaskRepository.save(savedTask);
        agentTaskEventService.appendEvent(savedTask.getId(), null, "TASK_DUPLICATED", "INFO",
                "Task duplicated from " + sourceTask.getTaskNo() + ".", sourceTask.getTaskNo());
        return getTask(savedTask.getId());
    }

    @Transactional
    public AgentTaskResponse createFollowUpTask(Long taskId) {
        AgentTask sourceTask = refreshTaskSummary(findTask(taskId));
        AgentTaskResponse sourceTaskResponse = toResponse(sourceTask);
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setTitle(buildFollowUpTitle(sourceTask.getTitle()));
        request.setGoal(buildFollowUpGoal(sourceTaskResponse));
        request.setTaskType(sourceTask.getTaskType());
        request.setRepoId(sourceTask.getRepoId());
        request.setBusinessType(resolveFollowUpBusinessType(sourceTask.getBusinessType()));
        request.setAutoApproveLowRisk(sourceTask.getAutoApproveLowRisk());
        AgentTask savedTask = createTaskEntity(request);
        attachSourceTask(savedTask, sourceTask, "FOLLOWED_UP_FROM");
        savedTask = agentTaskRepository.save(savedTask);
        agentTaskEventService.appendEvent(savedTask.getId(), null, "FOLLOW_UP_TASK_CREATED", "INFO",
                "Follow-up task created from " + sourceTask.getTaskNo() + ".", sourceTask.getTaskNo());
        return getTask(savedTask.getId());
    }

    @Transactional
    public AgentTaskResponse createFollowUpTaskAndStart(Long taskId) {
        AgentTask sourceTask = refreshTaskSummary(findTask(taskId));
        sourceTask = refreshTaskHandoffContext(sourceTask);
        validateSourceTaskForFollowUpStart(sourceTask);
        AgentTaskResponse followUpTask = createFollowUpTask(taskId);
        return startTask(followUpTask.getTaskId());
    }

    private AgentTask createTaskEntity(AgentTaskCreateRequest request) {
        validateCreateRequest(request);
        AgentTask task = new AgentTask();
        task.setTaskNo(generateTaskNo());
        task.setTitle(resolveTaskTitle(request));
        task.setGoal(request.getGoal().trim());
        task.setTaskType(StrUtil.blankToDefault(request.getTaskType(), "GENERAL_AGENT"));
        task.setRepoId(StrUtil.emptyToNull(StrUtil.trim(request.getRepoId())));
        task.setBusinessType(resolveBusinessTypeValue(request.getBusinessType()));
        task.setStatus(AgentTaskStatus.PENDING);
        task.setAutoApproveLowRisk(Boolean.TRUE.equals(request.getAutoApproveLowRisk()));
        task.setCurrentRound(0);
        task.setMaxRound(8);
        task.setReplanCount(0);
        task.setConsecutiveSameReasonReplanCount(0);
        task.setMaxConsecutiveSameReasonReplanCount(3);
        task.setLastReplanReason(null);
        agentTaskWorkspaceService.initializeWorkspace(task);
        AgentTask savedTask = agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(savedTask.getId(), null, "WORKSPACE_READY", "INFO",
                "Task workspace has been prepared.", savedTask.getWorkspacePath());
        return savedTask;
    }

    public AgentTaskResponse getTask(Long taskId) {
        AgentTask task = agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task not found: " + taskId));
        task = refreshTaskSummary(task);
        task = refreshTaskHandoffContext(task);
        return toResponse(task);
    }

    public List<AgentTaskResponse> listTasks() {
        return agentTaskRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(this::refreshTaskSummary)
                .map(this::refreshTaskHandoffContext)
                .map(this::toResponse)
                .toList();
    }

    public List<AgentTaskResponse> listTasks(String status, String deliveryStatus, String sortBy) {
        return listTasks().stream()
                .filter(task -> StrUtil.isBlank(status) || StrUtil.equals(task.getStatus(), status))
                .filter(task -> StrUtil.isBlank(deliveryStatus) || StrUtil.equals(task.getDeliveryStatus(), deliveryStatus))
                .sorted(comparatorForSortBy(sortBy))
                .toList();
    }

    public AgentTaskOverviewResponse getTaskOverview(int limit) {
        List<AgentTaskResponse> tasks = listTasks();
        List<AgentTaskResponse> prioritizedTasks = tasks.stream()
                .sorted(comparatorForSortBy("backend_priority"))
                .limit(Math.max(limit, 1))
                .toList();
        int activeTaskCount = (int) tasks.stream()
                .filter(task -> List.of("IN_PROGRESS", "IN_REVIEW", "WAITING_APPROVAL", "WAITING_PLAN_CONFIRMATION")
                        .contains(task.getDeliveryStatus()))
                .count();
        int attentionTaskCount = (int) tasks.stream()
                .filter(task -> List.of("NEEDS_ATTENTION", "SUCCEEDED_NO_DELIVERABLE")
                        .contains(task.getDeliveryStatus()))
                .count();
        int deliveredTaskCount = (int) tasks.stream()
                .filter(task -> "DELIVERED".equals(task.getDeliveryStatus()))
                .count();
        return AgentTaskOverviewResponse.builder()
                .activeTaskCount(activeTaskCount)
                .attentionTaskCount(attentionTaskCount)
                .deliveredTaskCount(deliveredTaskCount)
                .prioritizedTasks(prioritizedTasks)
                .build();
    }

    @Transactional
    public AgentTaskResponse startTask(Long taskId) {
        AgentTask task = agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task not found: " + taskId));
        if (task.getStatus() == AgentTaskStatus.WAITING_PLAN_CONFIRMATION) {
            throw new IllegalStateException("Task is waiting for plan confirmation. Please confirm the revised plan first.");
        }
        if (task.getStatus() != AgentTaskStatus.PENDING && task.getStatus() != AgentTaskStatus.FAILED && task.getStatus() != AgentTaskStatus.BLOCKED) {
            throw new IllegalStateException("Task can only be started from PENDING, FAILED or BLOCKED status.");
        }
        task.setStatus(AgentTaskStatus.RUNNING);
        if (task.getStartedAt() == null) {
            task.setStartedAt(LocalDateTime.now());
        }
        task.setReviewSummary(null);
        task.setReviewSuggestedAction(null);
        task.setReviewSuggestedStepSeq(null);
        task.setFinalResult(null);
        task.setErrorMessage(null);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(task.getId(), null, "TASK_STARTED", "INFO",
                "Task runtime has been started. Planning and execution engine will be connected next.", null);
        agentTaskRuntimeService.startRuntime(task.getId());
        return getTask(task.getId());
    }

    @Transactional
    public AgentTaskResponse pauseTask(Long taskId) {
        AgentTask task = findTask(taskId);
        if (task.getStatus() != AgentTaskStatus.RUNNING && task.getStatus() != AgentTaskStatus.PLANNING && task.getStatus() != AgentTaskStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("Only RUNNING, PLANNING or WAITING_APPROVAL tasks can be paused.");
        }
        task.setStatus(AgentTaskStatus.BLOCKED);
        task.setErrorMessage("Task paused by user.");
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, null, "TASK_PAUSED", "WARN", "Task paused by user.", null);
        return getTask(taskId);
    }

    @Transactional
    public AgentTaskResponse resumeTask(Long taskId) {
        AgentTask task = findTask(taskId);
        if (task.getStatus() != AgentTaskStatus.BLOCKED && task.getStatus() != AgentTaskStatus.WAITING_APPROVAL && task.getStatus() != AgentTaskStatus.REVIEWING) {
            throw new IllegalStateException("Only BLOCKED, WAITING_APPROVAL or REVIEWING tasks can be resumed.");
        }
        task.setStatus(AgentTaskStatus.RUNNING);
        task.setErrorMessage(null);
        task.setReviewSummary(null);
        task.setReviewSuggestedAction(null);
        task.setReviewSuggestedStepSeq(null);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, null, "TASK_RESUMED", "INFO", "Task resumed by user.", null);
        agentTaskRuntimeService.startRuntime(taskId);
        return getTask(taskId);
    }

    @Transactional
    public AgentTaskResponse reviewTask(Long taskId) {
        AgentTask task = findTask(taskId);
        if (task.getStatus() == AgentTaskStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled tasks cannot be reviewed.");
        }
        task.setStatus(AgentTaskStatus.REVIEWING);
        task.setErrorMessage(null);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, null, "TASK_REVIEW_REQUESTED", "INFO",
                "Manual review requested by user.", null);
        agentTaskRuntimeService.finishReview(taskId);
        return getTask(taskId);
    }

    @Transactional
    public AgentTaskResponse replanTask(Long taskId) {
        AgentTask task = findTask(taskId);
        if (task.getStatus() == AgentTaskStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled tasks cannot be replanned.");
        }
        agentTaskRuntimeService.replanTask(taskId);
        agentTaskEventService.appendEvent(taskId, null, "TASK_REPLAN_REQUESTED", "INFO",
                "Manual replan requested by user.", null);
        return getTask(taskId);
    }

    @Transactional
    public AgentTaskResponse confirmPlan(Long taskId) {
        AgentTask task = findTask(taskId);
        if (task.getStatus() != AgentTaskStatus.WAITING_PLAN_CONFIRMATION) {
            throw new IllegalStateException("Only tasks waiting for plan confirmation can be confirmed.");
        }
        task.setStatus(AgentTaskStatus.RUNNING);
        task.setErrorMessage(null);
        task.setReviewSummary(null);
        task.setReviewSuggestedAction(null);
        task.setReviewSuggestedStepSeq(null);
        if (task.getStartedAt() == null) {
            task.setStartedAt(LocalDateTime.now());
        }
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, null, "PLAN_CONFIRMED", "INFO",
                "Revised plan confirmed by user. Execution will continue.", task.getPlanSummary());
        agentTaskRuntimeService.startRuntime(taskId);
        return getTask(taskId);
    }

    @Transactional
    public AgentTaskResponse cancelTask(Long taskId) {
        AgentTask task = findTask(taskId);
        if (task.getStatus() == AgentTaskStatus.SUCCEEDED || task.getStatus() == AgentTaskStatus.CANCELLED) {
            throw new IllegalStateException("Completed or cancelled tasks cannot be cancelled again.");
        }
        task.setStatus(AgentTaskStatus.CANCELLED);
        task.setFinishedAt(LocalDateTime.now());
        task.setErrorMessage("Task cancelled by user.");
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, null, "TASK_CANCELLED", "WARN", "Task cancelled by user.", null);
        return getTask(taskId);
    }

    private void validateCreateRequest(AgentTaskCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (StrUtil.isBlank(request.getGoal())) {
            throw new IllegalArgumentException("goal is required.");
        }
        AgentTaskBusinessType businessType = AgentTaskBusinessType.fromValue(request.getBusinessType());
        if (businessType != null && StrUtil.isBlank(request.getRepoId())) {
            throw new IllegalArgumentException("repoId is required for code-related businessType.");
        }
        if (StrUtil.isNotBlank(request.getRepoId())) {
            repoRepository.findById(request.getRepoId().trim())
                    .orElseThrow(() -> new IllegalArgumentException("Repo not found: " + request.getRepoId()));
        }
    }

    private String generateTaskNo() {
        return "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private AgentTask findTask(Long taskId) {
        return agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task not found: " + taskId));
    }

    private AgentTaskResponse toResponse(AgentTask task) {
        Repo repo = null;
        if (StrUtil.isNotBlank(task.getRepoId())) {
            repo = repoRepository.findById(task.getRepoId()).orElse(null);
        }
        List<AgentTaskStepDto> steps = agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(task.getId())
                .stream()
                .map(step -> AgentTaskStepDto.builder()
                        .id(step.getId())
                        .stepSeq(step.getStepSeq())
                        .stepTitle(step.getStepTitle())
                        .stepType(step.getStepType())
                        .assignedAgent(step.getAssignedAgent())
                        .toolName(step.getToolName())
                        .toolCategory(step.getToolCategory())
                        .riskLevel(step.getRiskLevel())
                        .status(step.getStatus().name())
                        .plannerOutput(step.getPlannerOutput())
                        .executorInput(step.getExecutorInput())
                        .executorOutput(step.getExecutorOutput())
                        .evidenceRefs(readEvidenceRefs(step.getEvidenceRefs()))
                        .requiresApproval(step.getRequiresApproval())
                        .retryCount(step.getRetryCount())
                        .maxRetry(step.getMaxRetry())
                        .startedAt(step.getStartedAt())
                        .finishedAt(step.getFinishedAt())
                        .createdAt(step.getCreatedAt())
                        .updatedAt(step.getUpdatedAt())
                        .build())
                .toList();

        List<AgentApprovalDto> approvals = agentApprovalRepository.findByTaskIdOrderByCreatedAtDesc(task.getId())
                .stream()
                .map(approval -> AgentApprovalDto.builder()
                        .id(approval.getId())
                        .approvalType(approval.getApprovalType())
                        .title(approval.getTitle())
                        .reason(approval.getReason())
                        .status(approval.getStatus().name())
                        .decisionBy(approval.getDecisionBy())
                        .decisionNote(approval.getDecisionNote())
                        .createdAt(approval.getCreatedAt())
                        .decidedAt(approval.getDecidedAt())
                        .build())
                .toList();

        List<AgentTaskEventDto> events = agentTaskEventService.listEventDtos(task.getId())
                .stream()
                .toList();

        List<AgentToolCallDto> toolCalls = agentToolCallService.listToolCalls(task.getId());
        List<AgentArtifactDto> artifacts = agentArtifactService.listArtifacts(task.getId());

        return AgentTaskResponse.builder()
                .taskId(task.getId())
                .taskNo(task.getTaskNo())
                .title(task.getTitle())
                .goal(task.getGoal())
                .taskType(task.getTaskType())
                .repoId(task.getRepoId())
                .repoName(repo == null ? null : repo.getName())
                .repoUrl(repo == null ? null : repo.getUrl())
                .businessType(task.getBusinessType())
                .status(task.getStatus().name())
                .autoApproveLowRisk(task.getAutoApproveLowRisk())
                .workspacePath(task.getWorkspacePath())
                .sourceTaskId(task.getSourceTaskId())
                .sourceTaskNo(task.getSourceTaskNo())
                .sourceTaskTitle(task.getSourceTaskTitle())
                .sourceTaskRelation(task.getSourceTaskRelation())
                .inheritedContextSummary(task.getInheritedContextSummary())
                .currentStepSeq(task.getCurrentStepSeq())
                .planSummary(task.getPlanSummary())
                .previousPlanSummary(task.getPreviousPlanSummary())
                .planDiffSummary(task.getPlanDiffSummary())
                .finalResult(task.getFinalResult())
                .handoffContextSummary(task.getHandoffContextSummary())
                .taskSummary(task.getTaskSummary())
                .deliveryStatus(task.getDeliveryStatus())
                .artifactCount(task.getArtifactCount())
                .deliverableArtifactCount(task.getDeliverableArtifactCount())
                .reviewSummary(task.getReviewSummary())
                .reviewSuggestedAction(task.getReviewSuggestedAction())
                .reviewSuggestedStepSeq(task.getReviewSuggestedStepSeq())
                .replanCount(task.getReplanCount())
                .consecutiveSameReasonReplanCount(task.getConsecutiveSameReasonReplanCount())
                .maxConsecutiveSameReasonReplanCount(task.getMaxConsecutiveSameReasonReplanCount())
                .lastReplanReason(task.getLastReplanReason())
                .errorMessage(task.getErrorMessage())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .previousPlanSteps(readList(task.getPreviousPlanStepsSnapshot(), new TypeReference<List<AgentPlanSnapshotStepDto>>() {}))
                .planDiffItems(readList(task.getPlanDiffSnapshot(), new TypeReference<List<AgentPlanDiffItemDto>>() {}))
                .artifacts(artifacts)
                .steps(steps)
                .approvals(approvals)
                .toolCalls(toolCalls)
                .events(events)
                .build();
    }

    private String resolveTaskTitle(AgentTaskCreateRequest request) {
        if (StrUtil.isNotBlank(request.getTitle())) {
            return request.getTitle().trim();
        }
        String goal = request.getGoal().trim().replaceAll("\\s+", " ");
        return goal.length() <= 60 ? goal : goal.substring(0, 60);
    }

    private String resolveBusinessTypeValue(String businessType) {
        AgentTaskBusinessType resolved = AgentTaskBusinessType.fromValue(businessType);
        return resolved == null ? null : resolved.name();
    }

    private AgentTask refreshTaskSummary(AgentTask task) {
        TaskSummarySnapshot snapshot = agentTaskSummaryService.buildSummary(task);
        boolean changed = !StrUtil.equals(task.getTaskSummary(), snapshot.taskSummary())
                || !StrUtil.equals(task.getDeliveryStatus(), snapshot.deliveryStatus())
                || !java.util.Objects.equals(task.getArtifactCount(), snapshot.artifactCount())
                || !java.util.Objects.equals(task.getDeliverableArtifactCount(), snapshot.deliverableArtifactCount());
        if (!changed) {
            return task;
        }
        task.setTaskSummary(snapshot.taskSummary());
        task.setDeliveryStatus(snapshot.deliveryStatus());
        task.setArtifactCount(snapshot.artifactCount());
        task.setDeliverableArtifactCount(snapshot.deliverableArtifactCount());
        return agentTaskRepository.save(task);
    }

    private AgentTask refreshTaskHandoffContext(AgentTask task) {
        String handoffContext = buildHandoffContext(task);
        if (StrUtil.equals(task.getHandoffContextSummary(), handoffContext)) {
            return task;
        }
        task.setHandoffContextSummary(handoffContext);
        return agentTaskRepository.save(task);
    }

    private int priorityForDeliveryStatus(String deliveryStatus) {
        if (List.of("NEEDS_ATTENTION", "SUCCEEDED_NO_DELIVERABLE").contains(deliveryStatus)) {
            return 0;
        }
        if (List.of("IN_PROGRESS", "IN_REVIEW", "WAITING_APPROVAL", "WAITING_PLAN_CONFIRMATION").contains(deliveryStatus)) {
            return 1;
        }
        if ("DELIVERED".equals(deliveryStatus)) {
            return 2;
        }
        if ("NOT_STARTED".equals(deliveryStatus)) {
            return 3;
        }
        return 4;
    }

    private String buildDuplicateTitle(String title) {
        return StrUtil.blankToDefault(title, "Untitled task") + " (Copy)";
    }

    private void validateSourceTaskForFollowUpStart(AgentTask sourceTask) {
        if (sourceTask.getStatus() != AgentTaskStatus.SUCCEEDED) {
            throw new IllegalStateException("Follow-up can only start automatically after the source task has succeeded.");
        }
        if (StrUtil.equals(sourceTask.getReviewSuggestedAction(), "STRENGTHEN_HANDOFF")) {
            throw new IllegalStateException("Strengthen the source task handoff first before starting a follow-up automatically.");
        }
        if (StrUtil.isBlank(sourceTask.getHandoffContextSummary()) || sourceTask.getHandoffContextSummary().length() < 160) {
            throw new IllegalStateException("The source task does not have a strong enough handoff package yet. Strengthen the handoff before auto-starting a follow-up.");
        }
        if (!StrUtil.equals(sourceTask.getDeliveryStatus(), "DELIVERED")) {
            throw new IllegalStateException("Follow-up can only start automatically after the source task has a delivered result.");
        }
    }

    private String buildFollowUpTitle(String title) {
        return "Follow-up: " + StrUtil.blankToDefault(title, "Untitled task");
    }

    private String buildFollowUpGoal(AgentTaskResponse sourceTask) {
        if ("BUG_DIAGNOSIS".equalsIgnoreCase(sourceTask.getBusinessType())) {
            return """
                    Based on the previous bug diagnosis task, generate a grounded patch suggestion.

                    Previous task title: %s
                    Previous task goal: %s
                    Previous delivery status: %s
                    Previous handoff context: %s

                    Reuse the previous diagnosis context, confirm the grounded code evidence, and produce:
                    - files to change
                    - patch plan
                    - diff preview
                    - test suggestions
                    - risks
                    Do not modify source files automatically.
                    """.formatted(
                    sourceTask.getTitle(),
                    sourceTask.getGoal(),
                    StrUtil.blankToDefault(sourceTask.getDeliveryStatus(), "UNKNOWN"),
                    StrUtil.blankToDefault(sourceTask.getHandoffContextSummary(), "No structured handoff context available.")
            ).trim();
        }
        StringBuilder goal = new StringBuilder();
        goal.append("Continue the work from the previous task and produce the next useful deliverable.\n\n");
        goal.append("Previous task title: ").append(sourceTask.getTitle()).append("\n");
        goal.append("Previous task goal: ").append(sourceTask.getGoal()).append("\n");
        goal.append("Previous delivery status: ")
                .append(StrUtil.blankToDefault(sourceTask.getDeliveryStatus(), "UNKNOWN"))
                .append("\n");
        goal.append("Previous handoff context: ")
                .append(StrUtil.blankToDefault(sourceTask.getHandoffContextSummary(), "No structured handoff context available."))
                .append("\n");
        goal.append("\nUse the previous task outputs as context, decide the most practical next step, and deliver a concrete follow-up result.");
        return goal.toString();
    }

    private String buildInheritedContextSummary(AgentTaskResponse sourceTask, String relation) {
        String relationLabel = switch (relation) {
            case "DUPLICATED_FROM" -> "This task duplicates a previous task and should reuse the same working context.";
            case "FOLLOWED_UP_FROM" -> "This task follows up on a previous task and should continue from its best available result.";
            default -> "This task inherits context from a previous task.";
        };
        StringBuilder summary = new StringBuilder();
        summary.append(relationLabel).append("\n");
        summary.append("Source task: ")
                .append(StrUtil.blankToDefault(sourceTask.getTaskNo(), "UNKNOWN"))
                .append(" - ")
                .append(StrUtil.blankToDefault(sourceTask.getTitle(), "Untitled task"))
                .append("\n");
        summary.append("Source goal: ").append(StrUtil.blankToDefault(sourceTask.getGoal(), "No source goal available.")).append("\n");
        summary.append("Source handoff context: ")
                .append(StrUtil.blankToDefault(sourceTask.getHandoffContextSummary(), "No source handoff context available."))
                .append("\n");
        summary.append("Treat this inherited context as prior work that can be reused during planning, execution, and review.");
        return summary.toString();
    }

    private String resolveFollowUpBusinessType(String sourceBusinessType) {
        if ("BUG_DIAGNOSIS".equalsIgnoreCase(sourceBusinessType)) {
            return AgentTaskBusinessType.PATCH_SUGGESTION.name();
        }
        return sourceBusinessType;
    }

    private String buildHandoffContext(AgentTask task) {
        List<AgentArtifactDto> artifacts = agentArtifactService.listArtifacts(task.getId());
        String artifactNames = artifacts.stream()
                .map(AgentArtifactDto::getArtifactName)
                .filter(StrUtil::isNotBlank)
                .limit(5)
                .reduce((left, right) -> left + ", " + right)
                .orElse("No named artifacts recorded.");
        return """
                Task: %s
                Goal: %s
                Delivery status: %s
                Summary: %s
                Review: %s
                Final result: %s
                Artifacts: %s
                """.formatted(
                StrUtil.blankToDefault(task.getTitle(), "Untitled task"),
                StrUtil.blankToDefault(task.getGoal(), "No goal available."),
                StrUtil.blankToDefault(task.getDeliveryStatus(), "UNKNOWN"),
                StrUtil.blankToDefault(task.getTaskSummary(), "No summary available."),
                StrUtil.blankToDefault(task.getReviewSummary(), "No review conclusion available."),
                StrUtil.blankToDefault(task.getFinalResult(), "No final result was accepted yet."),
                artifactNames
        ).trim();
    }

    private void attachSourceTask(AgentTask task, AgentTask sourceTask, String relation) {
        task.setSourceTaskId(sourceTask.getId());
        task.setSourceTaskNo(sourceTask.getTaskNo());
        task.setSourceTaskTitle(sourceTask.getTitle());
        task.setSourceTaskRelation(relation);
    }

    private Comparator<AgentTaskResponse> comparatorForSortBy(String sortBy) {
        if (StrUtil.equalsAny(sortBy, "backend_priority", "delivery_first")) {
            return Comparator
                    .comparingInt((AgentTaskResponse task) -> priorityForDeliveryStatus(task.getDeliveryStatus()))
                    .thenComparing(AgentTaskResponse::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(AgentTaskResponse::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        }
        return Comparator
                .comparing(AgentTaskResponse::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AgentTaskResponse::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> typeReference) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse task snapshot payload.", e);
        }
    }

    private List<EvidenceRefDto> readEvidenceRefs(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EvidenceRefDto>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse task step evidence refs.", e);
        }
    }
}
