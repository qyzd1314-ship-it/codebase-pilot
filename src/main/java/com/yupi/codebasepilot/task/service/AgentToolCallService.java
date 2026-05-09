package com.yupi.codebasepilot.task.service;

import com.yupi.codebasepilot.task.dto.AgentToolCallDto;
import com.yupi.codebasepilot.task.entity.AgentToolCall;
import com.yupi.codebasepilot.task.repository.AgentToolCallRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AgentToolCallService {

    private final AgentToolCallRepository agentToolCallRepository;

    public AgentToolCallService(AgentToolCallRepository agentToolCallRepository) {
        this.agentToolCallRepository = agentToolCallRepository;
    }

    public AgentToolCall createToolCall(Long taskId, Long stepId, String toolName, String toolCategory,
                                        String riskLevel, String requestPayload) {
        AgentToolCall toolCall = new AgentToolCall();
        toolCall.setTaskId(taskId);
        toolCall.setStepId(stepId);
        toolCall.setToolName(toolName);
        toolCall.setToolCategory(toolCategory);
        toolCall.setRiskLevel(riskLevel);
        toolCall.setRequestPayload(requestPayload);
        toolCall.setSuccess(false);
        toolCall.setStartedAt(LocalDateTime.now());
        return agentToolCallRepository.save(toolCall);
    }

    public AgentToolCall completeToolCall(AgentToolCall toolCall, String responsePayload, boolean success, String errorMessage) {
        toolCall.setResponsePayload(responsePayload);
        toolCall.setSuccess(success);
        toolCall.setErrorMessage(errorMessage);
        toolCall.setFinishedAt(LocalDateTime.now());
        return agentToolCallRepository.save(toolCall);
    }

    public List<AgentToolCallDto> listToolCalls(Long taskId) {
        return agentToolCallRepository.findByTaskIdOrderByCreatedAtAsc(taskId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private AgentToolCallDto toDto(AgentToolCall toolCall) {
        return AgentToolCallDto.builder()
                .id(toolCall.getId())
                .stepId(toolCall.getStepId())
                .toolName(toolCall.getToolName())
                .toolCategory(toolCall.getToolCategory())
                .riskLevel(toolCall.getRiskLevel())
                .requestPayload(toolCall.getRequestPayload())
                .responsePayload(toolCall.getResponsePayload())
                .success(toolCall.getSuccess())
                .errorMessage(toolCall.getErrorMessage())
                .startedAt(toolCall.getStartedAt())
                .finishedAt(toolCall.getFinishedAt())
                .createdAt(toolCall.getCreatedAt())
                .build();
    }
}
