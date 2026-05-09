package com.yupi.codebasepilot.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.codebasepilot.task.dto.EvidenceRefDto;
import com.yupi.codebasepilot.task.dto.AgentTaskStepDto;
import com.yupi.codebasepilot.task.entity.AgentTask;
import com.yupi.codebasepilot.task.entity.AgentTaskStep;
import com.yupi.codebasepilot.task.enums.AgentTaskStatus;
import com.yupi.codebasepilot.task.enums.AgentTaskStepStatus;
import com.yupi.codebasepilot.task.repository.AgentTaskStepRepository;
import com.yupi.codebasepilot.task.repository.AgentTaskRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentStepService {

    private final AgentTaskStepRepository agentTaskStepRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final AgentTaskEventService agentTaskEventService;
    private final AgentTaskRuntimeService agentTaskRuntimeService;
    private final ObjectMapper objectMapper;

    public AgentStepService(AgentTaskStepRepository agentTaskStepRepository,
                            AgentTaskRepository agentTaskRepository,
                            AgentTaskEventService agentTaskEventService,
                            AgentTaskRuntimeService agentTaskRuntimeService,
                            ObjectMapper objectMapper) {
        this.agentTaskStepRepository = agentTaskStepRepository;
        this.agentTaskRepository = agentTaskRepository;
        this.agentTaskEventService = agentTaskEventService;
        this.agentTaskRuntimeService = agentTaskRuntimeService;
        this.objectMapper = objectMapper;
    }

    public List<AgentTaskStepDto> listSteps(Long taskId) {
        return agentTaskStepRepository.findByTaskIdOrderByStepSeqAsc(taskId)
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
    }

    @Transactional
    public AgentTaskStepDto retryStep(Long taskId, Long stepId) {
        AgentTask task = agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task not found: " + taskId));
        AgentTaskStep step = agentTaskStepRepository.findById(stepId)
                .orElseThrow(() -> new EntityNotFoundException("Step not found: " + stepId));
        if (!taskId.equals(step.getTaskId())) {
            throw new IllegalArgumentException("The step does not belong to the specified task.");
        }
        if (step.getStatus() != AgentTaskStepStatus.FAILED) {
            throw new IllegalStateException("Only FAILED steps can be retried.");
        }
        if (task.getStatus() == AgentTaskStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled tasks cannot retry steps.");
        }

        step.setStatus(AgentTaskStepStatus.RETRYING);
        step.setRetryCount(step.getRetryCount() == null ? 1 : step.getRetryCount() + 1);
        step.setStartedAt(null);
        step.setFinishedAt(null);
        step.setExecutorInput(null);
        step.setExecutorOutput(null);
        step.setEvidenceRefs(null);
        agentTaskStepRepository.save(step);

        task.setStatus(AgentTaskStatus.RUNNING);
        task.setErrorMessage(null);
        task.setFinishedAt(null);
        task.setFinalResult(null);
        task.setReviewSummary(null);
        task.setReviewSuggestedAction(null);
        task.setReviewSuggestedStepSeq(null);
        task.setCurrentStepSeq(step.getStepSeq());
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(taskId, stepId, "STEP_RETRY_REQUESTED", "WARN",
                "Retry requested for step " + step.getStepSeq() + ".", "Retry count: " + step.getRetryCount());

        step.setStatus(AgentTaskStepStatus.PENDING);
        agentTaskStepRepository.save(step);
        agentTaskRuntimeService.startRuntime(taskId);
        return listSteps(taskId).stream()
                .filter(item -> stepId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Step not found after retry: " + stepId));
    }

    private List<EvidenceRefDto> readEvidenceRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EvidenceRefDto>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse step evidence refs.", e);
        }
    }
}
