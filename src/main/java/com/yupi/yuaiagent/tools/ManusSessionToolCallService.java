package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.tools.entity.ManusSessionToolCallEntity;
import com.yupi.yuaiagent.tools.repository.ManusSessionToolCallRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ManusSessionToolCallService {

    private final ManusSessionToolCallRepository manusSessionToolCallRepository;

    public ManusSessionToolCallService(ManusSessionToolCallRepository manusSessionToolCallRepository) {
        this.manusSessionToolCallRepository = manusSessionToolCallRepository;
    }

    public ManusToolCall recordToolCall(String sessionId,
                                        String toolName,
                                        String toolCategory,
                                        String riskLevel,
                                        String requestPayload,
                                        String responsePayload,
                                        boolean success,
                                        String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        ManusSessionToolCallEntity entity = new ManusSessionToolCallEntity();
        entity.setSessionId(sessionId);
        entity.setToolName(toolName);
        entity.setToolCategory(toolCategory);
        entity.setRiskLevel(riskLevel);
        entity.setRequestPayload(sanitize(requestPayload));
        entity.setResponsePayload(sanitize(responsePayload));
        entity.setSuccess(success);
        entity.setErrorMessage(sanitize(errorMessage));
        entity.setStartedAt(now);
        entity.setFinishedAt(now);
        ManusSessionToolCallEntity saved = manusSessionToolCallRepository.save(entity);
        return toRecord(saved);
    }

    public List<ManusToolCall> listToolCalls(String sessionId) {
        return manusSessionToolCallRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toRecord)
                .toList();
    }

    private ManusToolCall toRecord(ManusSessionToolCallEntity entity) {
        return new ManusToolCall(
                String.valueOf(entity.getId()),
                entity.getSessionId(),
                entity.getToolName(),
                entity.getToolCategory(),
                entity.getRiskLevel(),
                entity.getRequestPayload(),
                entity.getResponsePayload(),
                entity.getSuccess(),
                entity.getErrorMessage(),
                entity.getStartedAt(),
                entity.getFinishedAt()
        );
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").trim();
    }

    public record ManusToolCall(
            String id,
            String sessionId,
            String toolName,
            String toolCategory,
            String riskLevel,
            String requestPayload,
            String responsePayload,
            boolean success,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
    }
}
