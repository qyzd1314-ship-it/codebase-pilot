package com.yupi.yuaiagent.task.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.task.agent.Agent;
import com.yupi.yuaiagent.task.agent.AgentContext;
import com.yupi.yuaiagent.task.agent.AgentResult;
import com.yupi.yuaiagent.task.agent.AgentStepSummary;
import com.yupi.yuaiagent.task.agent.NextAction;
import com.yupi.yuaiagent.task.agent.StoredAgentResult;
import com.yupi.yuaiagent.task.agent.impl.PlannerAgent;
import com.yupi.yuaiagent.task.dto.EvidenceRefDto;
import com.yupi.yuaiagent.task.dto.RepoProfileDto;
import com.yupi.yuaiagent.task.dto.UnderstandingPlanDto;
import com.yupi.yuaiagent.task.entity.AgentTask;
import com.yupi.yuaiagent.task.entity.AgentTaskStep;
import com.yupi.yuaiagent.task.enums.AgentTaskStatus;
import com.yupi.yuaiagent.task.enums.AgentTaskStepStatus;
import com.yupi.yuaiagent.task.enums.CodeUnderstandingIntent;
import com.yupi.yuaiagent.task.repository.AgentTaskRepository;
import com.yupi.yuaiagent.task.repository.AgentTaskStepRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CodebaseAgentOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(CodebaseAgentOrchestratorService.class);
    private static final int DEFAULT_MAX_CONSECUTIVE_SAME_REASON_REPLANS = 3;

    private final AgentTaskRepository agentTaskRepository;
    private final AgentTaskStepRepository agentTaskStepRepository;
    private final AgentTaskEventService agentTaskEventService;
    private final ObjectMapper objectMapper;
    private final PlannerAgent plannerAgent;
    private final Map<String, Agent> agentRegistry;
    private final RepoProfiler repoProfiler;
    private final UnderstandingIntentPlanner understandingIntentPlanner;

    public CodebaseAgentOrchestratorService(AgentTaskRepository agentTaskRepository,
                                            AgentTaskStepRepository agentTaskStepRepository,
                                            AgentTaskEventService agentTaskEventService,
                                            ObjectMapper objectMapper,
                                            PlannerAgent plannerAgent,
                                            RepoProfiler repoProfiler,
                                            UnderstandingIntentPlanner understandingIntentPlanner,
                                            List<Agent> agents) {
        this.agentTaskRepository = agentTaskRepository;
        this.agentTaskStepRepository = agentTaskStepRepository;
        this.agentTaskEventService = agentTaskEventService;
        this.objectMapper = objectMapper;
        this.plannerAgent = plannerAgent;
        this.repoProfiler = repoProfiler;
        this.understandingIntentPlanner = understandingIntentPlanner;
        this.agentRegistry = agents.stream().collect(LinkedHashMap::new, (map, agent) -> map.put(agent.name(), agent), Map::putAll);
    }

    public void runTask(Long taskId) {
        try {
            ensureInitialPlan(taskId);
            while (true) {
                AgentTask task = findTask(taskId);
                if (isTerminal(task)) {
                    return;
                }
                Optional<AgentTaskStep> nextStep = findNextRunnableStep(taskId);
                if (nextStep.isEmpty()) {
                    return;
                }
                executeStep(task.getId(), nextStep.get().getId());
            }
        } catch (Exception e) {
            log.error("Codebase orchestrator failed for task {}", taskId, e);
            handleUnexpectedFailure(taskId, e);
        }
    }

    @Transactional
    public void replanTask(Long taskId) {
        replanTask(taskId, true);
    }

    @Transactional
    public void manualReplanTask(Long taskId) {
        replanTask(taskId, false);
    }

    @Transactional
    protected void replanTask(Long taskId, boolean preserveConvergenceGuard) {
        AgentTask task = findTask(taskId);
        List<AgentTaskStep> steps = agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(taskId);
        AgentTaskStep plannerStep = steps.stream()
                .filter(step -> "PlannerAgent".equals(step.getAssignedAgent()))
                .findFirst()
                .orElseGet(() -> createPlannerStep(task));
        List<AgentTaskStep> staleSteps = steps.stream()
                .filter(step -> !plannerStep.getId().equals(step.getId()))
                .toList();
        if (!staleSteps.isEmpty()) {
            agentTaskStepRepository.deleteAll(staleSteps);
        }
        plannerStep.setStatus(AgentTaskStepStatus.PENDING);
        plannerStep.setStartedAt(null);
        plannerStep.setFinishedAt(null);
        plannerStep.setExecutorInput(null);
        plannerStep.setExecutorOutput(null);
        plannerStep.setEvidenceRefs(null);
        plannerStep.setPlannerOutput("Replanning requested by orchestrator.");
        agentTaskStepRepository.save(plannerStep);
        task.setStatus(AgentTaskStatus.RUNNING);
        task.setCurrentRound(0);
        task.setCurrentStepSeq(plannerStep.getStepSeq());
        task.setErrorMessage(null);
        task.setFinalResult(null);
        task.setReviewSummary(null);
        task.setReviewSuggestedAction(null);
        task.setReviewSuggestedStepSeq(null);
        if (!preserveConvergenceGuard) {
            resetReplanConvergenceState(task);
        }
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, plannerStep.getId(), "TASK_REPLAN_REQUESTED", "WARN",
                "Reviewer or search agent requested a replan. Planner will rebuild the fixed %s workflow."
                        .formatted(workflowLabel(task.getBusinessType())), null);
    }

    @Transactional
    public void ensureInitialPlan(Long taskId) {
        AgentTask task = findTask(taskId);
        if (!agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(taskId).isEmpty()) {
            return;
        }
        task.setStatus(AgentTaskStatus.PLANNING);
        agentTaskRepository.save(task);
        AgentTaskStep plannerStep = createPlannerStep(task);
        task.setPlanSummary("PlannerAgent will generate the fixed %s workflow."
                .formatted(workflowLabel(task.getBusinessType())));
        task.setStatus(AgentTaskStatus.RUNNING);
        task.setCurrentStepSeq(plannerStep.getStepSeq());
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, plannerStep.getId(), "PLAN_CREATED", "INFO",
                "Initial planner step created for the %s workflow."
                        .formatted(workflowDisplayName(task.getBusinessType())), null);
    }

    @Transactional
    public void executeStep(Long taskId, Long stepId) {
        AgentTask task = findTask(taskId);
        AgentTaskStep step = agentTaskStepRepository.findById(stepId)
                .orElseThrow(() -> new EntityNotFoundException("Step not found: " + stepId));
        if (!taskId.equals(step.getTaskId())) {
            throw new IllegalArgumentException("The step does not belong to the specified task.");
        }
        if (task.getCurrentRound() >= task.getMaxRound()) {
            blockForHuman(task, step, "Task exceeded maxRound and now requires human intervention.");
            return;
        }
        task.setCurrentRound(task.getCurrentRound() + 1);
        task.setCurrentStepSeq(step.getStepSeq());
        step.setStatus(AgentTaskStepStatus.RUNNING);
        step.setStartedAt(LocalDateTime.now());
        step.setFinishedAt(null);
        step.setExecutorInput(writeJson(buildExecutorInput(task, step)));
        agentTaskRepository.save(task);
        agentTaskStepRepository.save(step);
        agentTaskEventService.appendEvent(taskId, stepId, "AGENT_STEP_STARTED", "INFO",
                "Running %s on step %d.".formatted(step.getAssignedAgent(), step.getStepSeq()), null);

        Agent agent = agentRegistry.get(step.getAssignedAgent());
        if (agent == null) {
            failTask(task, step, "No agent registered for " + step.getAssignedAgent());
            return;
        }

        AgentResult result = agent.run(buildContext(task, step));
        step.setEvidenceRefs(writeJson(result.getEvidenceRefs()));
        step.setExecutorOutput(writeJson(StoredAgentResult.builder()
                .summary(result.getSummary())
                .structuredOutput(result.getStructuredOutput())
                .confidence(result.getConfidence())
                .nextAction(result.getNextAction() == null ? null : result.getNextAction().name())
                .failureReason(result.getFailureReason())
                .evidenceRefs(result.getEvidenceRefs())
                .build()));
        step.setFinishedAt(LocalDateTime.now());
        agentTaskStepRepository.save(step);
        handleNextAction(task, step, result);
    }

    private void handleNextAction(AgentTask task, AgentTaskStep step, AgentResult result) {
        NextAction nextAction = result.getNextAction() == null ? NextAction.FAIL : result.getNextAction();
        switch (nextAction) {
            case CONTINUE -> handleContinue(task, step, result);
            case DELIVER -> handleDeliver(task, step, result);
            case RETRY -> handleRetry(task, step, result);
            case REPLAN -> handleReplan(task, step, result);
            case NEED_HUMAN_APPROVAL -> blockForHuman(task, step, defaultFailureMessage(result, "Human approval required."));
            case FAIL -> failTask(task, step, defaultFailureMessage(result, "Agent step failed."));
        }
    }

    private void handleContinue(AgentTask task, AgentTaskStep step, AgentResult result) {
        step.setStatus(AgentTaskStepStatus.SUCCEEDED);
        agentTaskStepRepository.save(step);
        if ("PlannerAgent".equals(step.getAssignedAgent())) {
            appendPlannedSteps(task, step, result);
        }
        task.setStatus(AgentTaskStatus.RUNNING);
        task.setErrorMessage(null);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(task.getId(), step.getId(), "AGENT_STEP_COMPLETED", "INFO",
                StrUtil.blankToDefault(result.getSummary(), "Step completed."), null);
    }

    private void handleDeliver(AgentTask task, AgentTaskStep step, AgentResult result) {
        step.setStatus(AgentTaskStepStatus.SUCCEEDED);
        agentTaskStepRepository.save(step);
        if ("DeliveryAgent".equals(step.getAssignedAgent())) {
            task.setStatus(AgentTaskStatus.SUCCEEDED);
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(null);
            task.setFinalResult(result.getSummary());
            task.setReviewSummary(reviewSummaryForTask(task.getBusinessType()));
            task.setReviewSuggestedAction("NONE");
            agentTaskRepository.save(task);
            agentTaskEventService.appendEvent(task.getId(), step.getId(), "TASK_COMPLETED", "INFO",
                    "%s workflow completed and delivery artifacts were generated."
                            .formatted(workflowDisplayName(task.getBusinessType())), result.getSummary());
            return;
        }
        task.setStatus(AgentTaskStatus.RUNNING);
        task.setErrorMessage(null);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(task.getId(), step.getId(), "REVIEW_APPROVED", "INFO",
                StrUtil.blankToDefault(result.getSummary(), reviewApprovedMessage(task.getBusinessType())), null);
    }

    private void handleRetry(AgentTask task, AgentTaskStep step, AgentResult result) {
        int nextRetryCount = (step.getRetryCount() == null ? 0 : step.getRetryCount()) + 1;
        if (nextRetryCount > step.getMaxRetry()) {
            blockForHuman(task, step, "Step exceeded maxRetry after retry request: " + defaultFailureMessage(result, "Retry limit reached."));
            return;
        }
        step.setRetryCount(nextRetryCount);
        step.setStatus(AgentTaskStepStatus.PENDING);
        step.setStartedAt(null);
        step.setFinishedAt(null);
        agentTaskStepRepository.save(step);
        task.setStatus(AgentTaskStatus.RUNNING);
        task.setErrorMessage(defaultFailureMessage(result, "Retry requested."));
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(task.getId(), step.getId(), "AGENT_STEP_RETRY", "WARN",
                defaultFailureMessage(result, "Retry requested."), "retryCount=" + nextRetryCount);
    }

    private void handleReplan(AgentTask task, AgentTaskStep step, AgentResult result) {
        String replanReason = defaultFailureMessage(result, "Replan requested.");
        updateReplanConvergenceState(task, replanReason);
        step.setStatus(AgentTaskStepStatus.FAILED);
        agentTaskStepRepository.save(step);
        task.setErrorMessage(replanReason);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(task.getId(), step.getId(), "AGENT_STEP_REPLAN", "WARN",
                defaultFailureMessage(result, "Planner will regenerate the workflow."), null);
        if (task.getConsecutiveSameReasonReplanCount() > resolveMaxConsecutiveSameReasonReplanCount(task)) {
            blockForHuman(task, step, buildReplanConvergenceBlockMessage(task));
            return;
        }
        replanTask(task.getId());
    }

    private void appendPlannedSteps(AgentTask task, AgentTaskStep plannerStep, AgentResult result) {
        List<AgentTaskStep> existingSteps = agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(task.getId());
        if (existingSteps.size() > 1) {
            return;
        }
        List<PlannerAgent.PlannedAgentStep> plannedSteps = plannerAgent.buildSteps(task.getBusinessType());
        int nextSeq = plannerStep.getStepSeq() + 1;
        for (PlannerAgent.PlannedAgentStep plannedStep : plannedSteps) {
            AgentTaskStep step = new AgentTaskStep();
            step.setTaskId(task.getId());
            step.setStepSeq(nextSeq++);
            step.setStepTitle(plannedStep.getStepTitle());
            step.setStepType(plannedStep.getStepType());
            step.setAssignedAgent(plannedStep.getAssignedAgent());
            step.setToolName(plannedStep.getToolName());
            step.setToolCategory(plannedStep.getToolCategory());
            step.setRiskLevel(plannedStep.getRiskLevel());
            step.setStatus(AgentTaskStepStatus.PENDING);
            step.setPlannerOutput("Generated by PlannerAgent for %s."
                    .formatted(workflowLabel(task.getBusinessType())));
            step.setRetryCount(0);
            step.setMaxRetry(plannedStep.getMaxRetry());
            step.setRequiresApproval(false);
            AgentTaskStep savedStep = agentTaskStepRepository.save(step);
            agentTaskEventService.appendEvent(task.getId(), savedStep.getId(), "STEP_CREATED", "INFO",
                    "Created %s step: %s".formatted(savedStep.getAssignedAgent(), savedStep.getStepTitle()), null);
        }
        task.setPlanSummary(StrUtil.blankToDefault(result.getSummary(), "Planner generated the fixed task workflow."));
        agentTaskRepository.save(task);
    }

    private String reviewSummaryForTask(String businessType) {
        if ("CODE_UNDERSTANDING".equalsIgnoreCase(businessType)) {
            return "Reviewer approved the code understanding workflow and DeliveryAgent generated the module summary artifacts.";
        }
        if ("PATCH_SUGGESTION".equalsIgnoreCase(businessType)) {
            return "Reviewer approved the patch suggestion workflow and DeliveryAgent generated the final artifacts.";
        }
        return "Reviewer approved the diagnosis and DeliveryAgent generated the final artifacts.";
    }

    private String reviewApprovedMessage(String businessType) {
        if ("CODE_UNDERSTANDING".equalsIgnoreCase(businessType)) {
            return "Reviewer approved the module summary. Proceeding to delivery.";
        }
        if ("PATCH_SUGGESTION".equalsIgnoreCase(businessType)) {
            return "Reviewer approved the patch suggestion. Proceeding to delivery.";
        }
        return "Reviewer approved the diagnosis. Proceeding to delivery.";
    }

    private String workflowLabel(String businessType) {
        return businessType == null || businessType.isBlank() ? "BUG_DIAGNOSIS" : businessType.trim().toUpperCase();
    }

    private String workflowDisplayName(String businessType) {
        if ("CODE_UNDERSTANDING".equalsIgnoreCase(businessType)) {
            return "Code understanding";
        }
        if ("PATCH_SUGGESTION".equalsIgnoreCase(businessType)) {
            return "Patch suggestion";
        }
        return "Bug diagnosis";
    }

    private AgentContext buildContext(AgentTask task, AgentTaskStep step) {
        List<AgentTaskStep> steps = agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(task.getId());
        List<AgentStepSummary> previousSteps = steps.stream()
                .filter(item -> item.getStepSeq() < step.getStepSeq())
                .filter(item -> item.getStatus() == AgentTaskStepStatus.SUCCEEDED)
                .map(this::toStepSummary)
                .toList();
        List<EvidenceRefDto> evidenceRefs = previousSteps.stream()
                .flatMap(item -> item.getEvidenceRefs().stream())
                .toList();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("currentRetryCount", step.getRetryCount());
        memory.put("maxRetry", step.getMaxRetry());
        memory.put("currentRound", task.getCurrentRound());
        memory.put("maxRound", task.getMaxRound());
        if ("CODE_UNDERSTANDING".equalsIgnoreCase(task.getBusinessType())) {
            RepoProfileDto repoProfile = repoProfiler.buildProfile(task.getRepoId());
            UnderstandingPlanDto understandingPlan = understandingIntentPlanner.plan(task.getGoal(), repoProfile);
            memory.put("codeUnderstandingIntent", understandingPlan == null ? CodeUnderstandingIntent.OVERALL_STRUCTURE.name() : understandingPlan.getIntent());
            memory.put("understandingPlan", understandingPlan);
            memory.put("repoProfile", repoProfile);
        }
        previousSteps.stream()
                .filter(item -> item.getStructuredOutput() != null)
                .reduce((first, second) -> second)
                .ifPresent(last -> memory.put(last.getAssignedAgent() + "Output", last.getStructuredOutput()));
        return AgentContext.builder()
                .taskId(task.getId())
                .stepId(step.getId())
                .repoId(task.getRepoId())
                .businessType(task.getBusinessType())
                .userGoal(task.getGoal())
                .previousSteps(previousSteps)
                .evidenceRefs(evidenceRefs)
                .memory(memory)
                .build();
    }

    private AgentStepSummary toStepSummary(AgentTaskStep step) {
        StoredAgentResult storedResult = readStoredResult(step.getExecutorOutput());
        return AgentStepSummary.builder()
                .stepId(step.getId())
                .stepSeq(step.getStepSeq())
                .stepTitle(step.getStepTitle())
                .assignedAgent(step.getAssignedAgent())
                .summary(storedResult == null ? null : storedResult.summary())
                .structuredOutput(storedResult == null ? Map.of() : storedResult.structuredOutput())
                .evidenceRefs(readEvidenceRefs(step.getEvidenceRefs()))
                .confidence(storedResult == null ? null : storedResult.confidence())
                .nextAction(storedResult == null ? null : storedResult.nextAction())
                .failureReason(storedResult == null ? null : storedResult.failureReason())
                .build();
    }

    private Map<String, Object> buildExecutorInput(AgentTask task, AgentTaskStep step) {
        return Map.of(
                "taskId", task.getId(),
                "taskGoal", task.getGoal(),
                "repoId", StrUtil.blankToDefault(task.getRepoId(), ""),
                "assignedAgent", step.getAssignedAgent(),
                "stepTitle", step.getStepTitle(),
                "stepSeq", step.getStepSeq()
        );
    }

    private AgentTaskStep createPlannerStep(AgentTask task) {
        AgentTaskStep step = new AgentTaskStep();
        step.setTaskId(task.getId());
        step.setStepSeq(1);
        step.setStepTitle(plannerStepTitle(task.getBusinessType()));
        step.setStepType("PLANNING");
        step.setAssignedAgent("PlannerAgent");
        step.setToolName("PlannerAgent");
        step.setToolCategory("AGENT");
        step.setRiskLevel("LOW");
        step.setStatus(AgentTaskStepStatus.PENDING);
        step.setPlannerOutput("PlannerAgent will build the fixed task workflow.");
        step.setRetryCount(0);
        step.setMaxRetry(1);
        step.setRequiresApproval(false);
        return agentTaskStepRepository.save(step);
    }

    private String plannerStepTitle(String businessType) {
        if ("CODE_UNDERSTANDING".equalsIgnoreCase(businessType)) {
            return "Plan code understanding workflow";
        }
        if ("PATCH_SUGGESTION".equalsIgnoreCase(businessType)) {
            return "Plan patch suggestion workflow";
        }
        return "Plan bug diagnosis workflow";
    }

    private Optional<AgentTaskStep> findNextRunnableStep(Long taskId) {
        return agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(taskId).stream()
                .filter(step -> step.getStatus() == AgentTaskStepStatus.PENDING)
                .findFirst();
    }

    private void blockForHuman(AgentTask task, AgentTaskStep step, String message) {
        task.setStatus(AgentTaskStatus.BLOCKED);
        task.setErrorMessage(message);
        task.setReviewSuggestedAction("NEED_HUMAN_APPROVAL");
        task.setReviewSuggestedStepSeq(step.getStepSeq());
        agentTaskRepository.save(task);
        if (step != null) {
            step.setStatus(AgentTaskStepStatus.FAILED);
            step.setFinishedAt(LocalDateTime.now());
            agentTaskStepRepository.save(step);
        }
        agentTaskEventService.appendEvent(task.getId(), step == null ? null : step.getId(), "TASK_BLOCKED", "WARN", message, null);
    }

    private void failTask(AgentTask task, AgentTaskStep step, String message) {
        task.setStatus(AgentTaskStatus.FAILED);
        task.setErrorMessage(message);
        task.setFinishedAt(LocalDateTime.now());
        agentTaskRepository.save(task);
        step.setStatus(AgentTaskStepStatus.FAILED);
        step.setFinishedAt(LocalDateTime.now());
        agentTaskStepRepository.save(step);
        agentTaskEventService.appendEvent(task.getId(), step.getId(), "AGENT_STEP_FAILED", "ERROR", message, null);
    }

    @Transactional
    protected void handleUnexpectedFailure(Long taskId, Exception exception) {
        AgentTask task = agentTaskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        if (task.getStatus() == AgentTaskStatus.SUCCEEDED
                || task.getStatus() == AgentTaskStatus.CANCELLED) {
            return;
        }
        String message = "Unhandled orchestrator error: " + exception.getMessage();
        AgentTaskStep step = agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(taskId).stream()
                .filter(item -> item.getStatus() == AgentTaskStepStatus.RUNNING || item.getStatus() == AgentTaskStepStatus.PENDING)
                .findFirst()
                .orElse(null);
        task.setStatus(AgentTaskStatus.FAILED);
        task.setErrorMessage(message);
        task.setFinishedAt(LocalDateTime.now());
        agentTaskRepository.save(task);
        if (step != null) {
            step.setStatus(AgentTaskStepStatus.FAILED);
            if (step.getStartedAt() == null) {
                step.setStartedAt(LocalDateTime.now());
            }
            step.setFinishedAt(LocalDateTime.now());
            if (StrUtil.isBlank(step.getExecutorOutput())) {
                step.setExecutorOutput(message);
            }
            agentTaskStepRepository.save(step);
        }
        agentTaskEventService.appendEvent(taskId, step == null ? null : step.getId(),
                "TASK_ORCHESTRATOR_FAILED", "ERROR", message, null);
    }

    private boolean isTerminal(AgentTask task) {
        return task.getStatus() == AgentTaskStatus.SUCCEEDED
                || task.getStatus() == AgentTaskStatus.FAILED
                || task.getStatus() == AgentTaskStatus.CANCELLED
                || task.getStatus() == AgentTaskStatus.BLOCKED
                || task.getStatus() == AgentTaskStatus.WAITING_APPROVAL
                || task.getStatus() == AgentTaskStatus.WAITING_PLAN_CONFIRMATION;
    }

    private AgentTask findTask(Long taskId) {
        return agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task not found: " + taskId));
    }

    private String defaultFailureMessage(AgentResult result, String defaultMessage) {
        if (result == null) {
            return defaultMessage;
        }
        if (StrUtil.isNotBlank(result.getFailureReason())) {
            return result.getFailureReason();
        }
        if (StrUtil.isNotBlank(result.getSummary())) {
            return result.getSummary();
        }
        return defaultMessage;
    }

    private void updateReplanConvergenceState(AgentTask task, String replanReason) {
        String normalizedReason = StrUtil.blankToDefault(StrUtil.trim(replanReason), "Replan requested.");
        int currentReplanCount = task.getReplanCount() == null ? 0 : task.getReplanCount();
        int consecutiveCount = task.getConsecutiveSameReasonReplanCount() == null
                ? 0 : task.getConsecutiveSameReasonReplanCount();
        boolean sameReason = StrUtil.equals(task.getLastReplanReason(), normalizedReason);
        task.setReplanCount(currentReplanCount + 1);
        task.setConsecutiveSameReasonReplanCount(sameReason ? consecutiveCount + 1 : 1);
        task.setLastReplanReason(normalizedReason);
        if (task.getMaxConsecutiveSameReasonReplanCount() == null || task.getMaxConsecutiveSameReasonReplanCount() < 1) {
            task.setMaxConsecutiveSameReasonReplanCount(DEFAULT_MAX_CONSECUTIVE_SAME_REASON_REPLANS);
        }
    }

    private void resetReplanConvergenceState(AgentTask task) {
        task.setReplanCount(0);
        task.setConsecutiveSameReasonReplanCount(0);
        task.setLastReplanReason(null);
        if (task.getMaxConsecutiveSameReasonReplanCount() == null || task.getMaxConsecutiveSameReasonReplanCount() < 1) {
            task.setMaxConsecutiveSameReasonReplanCount(DEFAULT_MAX_CONSECUTIVE_SAME_REASON_REPLANS);
        }
    }

    private int resolveMaxConsecutiveSameReasonReplanCount(AgentTask task) {
        Integer value = task.getMaxConsecutiveSameReasonReplanCount();
        return value == null || value < 1 ? DEFAULT_MAX_CONSECUTIVE_SAME_REASON_REPLANS : value;
    }

    private String buildReplanConvergenceBlockMessage(AgentTask task) {
        return "Task exceeded repeated replan limit for the same reason (%d/%d): %s"
                .formatted(
                        task.getConsecutiveSameReasonReplanCount(),
                        resolveMaxConsecutiveSameReasonReplanCount(task),
                        StrUtil.blankToDefault(task.getLastReplanReason(), "unknown reason")
                );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize orchestrator payload.", e);
        }
    }

    private StoredAgentResult readStoredResult(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, StoredAgentResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse stored agent result.", e);
        }
    }

    private List<EvidenceRefDto> readEvidenceRefs(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EvidenceRefDto>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse stored evidence refs.", e);
        }
    }
}
