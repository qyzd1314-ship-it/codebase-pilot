package com.yupi.yuaiagent.task.service;

import com.yupi.yuaiagent.task.dto.AgentApprovalActionRequest;
import com.yupi.yuaiagent.task.dto.AgentApprovalDto;
import com.yupi.yuaiagent.task.entity.AgentApproval;
import com.yupi.yuaiagent.task.entity.AgentTask;
import com.yupi.yuaiagent.task.enums.AgentApprovalStatus;
import com.yupi.yuaiagent.task.enums.AgentTaskStatus;
import com.yupi.yuaiagent.task.repository.AgentApprovalRepository;
import com.yupi.yuaiagent.task.repository.AgentTaskRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AgentApprovalService {

    private final AgentApprovalRepository agentApprovalRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final AgentTaskEventService agentTaskEventService;
    private final AgentTaskRuntimeService agentTaskRuntimeService;

    public AgentApprovalService(AgentApprovalRepository agentApprovalRepository,
                                AgentTaskRepository agentTaskRepository,
                                AgentTaskEventService agentTaskEventService,
                                AgentTaskRuntimeService agentTaskRuntimeService) {
        this.agentApprovalRepository = agentApprovalRepository;
        this.agentTaskRepository = agentTaskRepository;
        this.agentTaskEventService = agentTaskEventService;
        this.agentTaskRuntimeService = agentTaskRuntimeService;
    }

    public List<AgentApprovalDto> listApprovals(Long taskId) {
        return agentApprovalRepository.findByTaskIdOrderByCreatedAtDesc(taskId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public AgentApprovalDto approve(Long approvalId, AgentApprovalActionRequest request) {
        AgentApproval approval = findApproval(approvalId);
        if (approval.getStatus() != AgentApprovalStatus.PENDING) {
            throw new IllegalStateException("Approval is already processed.");
        }
        approval.setStatus(AgentApprovalStatus.APPROVED);
        approval.setDecisionBy(getDecisionBy(request));
        approval.setDecisionNote(getDecisionNote(request, "Approved by user."));
        approval.setDecidedAt(LocalDateTime.now());
        agentApprovalRepository.save(approval);

        AgentTask task = findTask(approval.getTaskId());
        task.setStatus(AgentTaskStatus.RUNNING);
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(task.getId(), approval.getStepId(), "APPROVAL_APPROVED", "INFO",
                approval.getTitle(), approval.getDecisionNote());
        agentTaskRuntimeService.startRuntime(task.getId());
        return toDto(approval);
    }

    @Transactional
    public AgentApprovalDto reject(Long approvalId, AgentApprovalActionRequest request) {
        AgentApproval approval = findApproval(approvalId);
        if (approval.getStatus() != AgentApprovalStatus.PENDING) {
            throw new IllegalStateException("Approval is already processed.");
        }
        approval.setStatus(AgentApprovalStatus.REJECTED);
        approval.setDecisionBy(getDecisionBy(request));
        approval.setDecisionNote(getDecisionNote(request, "Rejected by user."));
        approval.setDecidedAt(LocalDateTime.now());
        agentApprovalRepository.save(approval);

        AgentTask task = findTask(approval.getTaskId());
        task.setStatus(AgentTaskStatus.BLOCKED);
        task.setErrorMessage("Task is blocked because an approval request was rejected.");
        agentTaskRepository.save(task);
        agentTaskEventService.appendEvent(task.getId(), approval.getStepId(), "APPROVAL_REJECTED", "WARN",
                approval.getTitle(), approval.getDecisionNote());
        return toDto(approval);
    }

    private AgentApproval findApproval(Long approvalId) {
        return agentApprovalRepository.findById(approvalId)
                .orElseThrow(() -> new EntityNotFoundException("Approval not found: " + approvalId));
    }

    private AgentTask findTask(Long taskId) {
        return agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task not found: " + taskId));
    }

    private String getDecisionBy(AgentApprovalActionRequest request) {
        return request != null && request.getDecisionBy() != null && !request.getDecisionBy().isBlank()
                ? request.getDecisionBy().trim()
                : "user";
    }

    private String getDecisionNote(AgentApprovalActionRequest request, String fallback) {
        return request != null && request.getDecisionNote() != null && !request.getDecisionNote().isBlank()
                ? request.getDecisionNote().trim()
                : fallback;
    }

    private AgentApprovalDto toDto(AgentApproval approval) {
        return AgentApprovalDto.builder()
                .id(approval.getId())
                .approvalType(approval.getApprovalType())
                .title(approval.getTitle())
                .reason(approval.getReason())
                .status(approval.getStatus().name())
                .decisionBy(approval.getDecisionBy())
                .decisionNote(approval.getDecisionNote())
                .createdAt(approval.getCreatedAt())
                .decidedAt(approval.getDecidedAt())
                .build();
    }
}
