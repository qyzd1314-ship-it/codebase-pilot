package com.yupi.yuaiagent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.tools.dto.ManusSessionMessageDto;
import com.yupi.yuaiagent.tools.entity.ManusSession;
import com.yupi.yuaiagent.tools.repository.ManusSessionRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ManusSessionMemoryService {

    private final ManusSessionRepository manusSessionRepository;
    private final ObjectMapper objectMapper;

    public ManusSessionMemoryService(ManusSessionRepository manusSessionRepository, ObjectMapper objectMapper) {
        this.manusSessionRepository = manusSessionRepository;
        this.objectMapper = objectMapper;
    }

    public List<Message> loadMessages(String sessionId) {
        return manusSessionRepository.findBySessionId(sessionId)
                .map(ManusSession::getMessageSnapshot)
                .map(this::deserializeMessages)
                .orElseGet(ArrayList::new);
    }

    public void saveMessages(String sessionId, List<Message> messages) {
        ManusSession manusSession = getOrCreateSession(sessionId);
        manusSession.setMessageSnapshot(serializeMessages(messages));
        manusSession.setStatus("ACTIVE");
        manusSessionRepository.save(manusSession);
    }

    public void ensureSession(String sessionId) {
        manusSessionRepository.save(getOrCreateSession(sessionId));
    }

    public void updateWorkspace(String sessionId, String workspacePath) {
        ManusSession manusSession = getOrCreateSession(sessionId);
        manusSession.setWorkspacePath(workspacePath);
        manusSession.setStatus("ACTIVE");
        manusSessionRepository.save(manusSession);
    }

    public void clearSession(String sessionId) {
        manusSessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setMessageSnapshot("[]");
            session.setStatus("CLEARED");
            manusSessionRepository.save(session);
        });
    }

    public List<ManusSessionMessageDto> listMessageSnapshots(String sessionId) {
        return manusSessionRepository.findBySessionId(sessionId)
                .map(ManusSession::getMessageSnapshot)
                .map(this::deserializeSnapshots)
                .orElseGet(ArrayList::new)
                .stream()
                .map(snapshot -> ManusSessionMessageDto.builder()
                        .role(snapshot.role())
                        .content(snapshot.content())
                        .messageType(snapshot.messageType())
                        .createdAt(snapshot.createdAt())
                        .build())
                .toList();
    }

    private ManusSession getOrCreateSession(String sessionId) {
        return manusSessionRepository.findBySessionId(sessionId)
                .orElseGet(() -> {
                    ManusSession manusSession = new ManusSession();
                    manusSession.setSessionId(sessionId);
                    manusSession.setDisplayName(sessionId);
                    manusSession.setStatus("ACTIVE");
                    manusSession.setMessageSnapshot("[]");
                    return manusSession;
                });
    }

    private String serializeMessages(List<Message> messages) {
        try {
            List<MessageSnapshot> snapshots = messages.stream()
                    .map(this::toSnapshot)
                    .toList();
            return objectMapper.writeValueAsString(snapshots);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize manus session messages", e);
        }
    }

    private List<Message> deserializeMessages(String snapshotJson) {
        List<MessageSnapshot> snapshots = deserializeSnapshots(snapshotJson);
        List<Message> messages = new ArrayList<>();
        for (MessageSnapshot snapshot : snapshots) {
            switch (snapshot.role()) {
                case "user" -> messages.add(new UserMessage(snapshot.content()));
                case "assistant", "tool" -> messages.add(new AssistantMessage(snapshot.content()));
                default -> {
                }
            }
        }
        return messages;
    }

    private List<MessageSnapshot> deserializeSnapshots(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(snapshotJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private MessageSnapshot toSnapshot(Message message) {
        long now = System.currentTimeMillis();
        if (message instanceof UserMessage userMessage) {
            return new MessageSnapshot("user", safeContent(userMessage.getText()), "user-question", now);
        }
        if (message instanceof AssistantMessage assistantMessage) {
            return new MessageSnapshot("assistant", safeContent(assistantMessage.getText()), "ai-answer", now);
        }
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            String content = toolResponseMessage.getResponses().stream()
                    .map(item -> item.name() + ": " + item.responseData())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            return new MessageSnapshot("tool", safeContent(content), "tool-result", now);
        }
        return new MessageSnapshot("assistant", safeContent(message.getText()), "ai-answer", now);
    }

    private String safeContent(String value) {
        return value == null ? "" : value;
    }

    private record MessageSnapshot(String role, String content, String messageType, Long createdAt) {
    }
}
