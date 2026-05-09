package com.yupi.codebasepilot.tools;

import com.yupi.codebasepilot.tools.entity.ManusSessionEventEntity;
import com.yupi.codebasepilot.tools.repository.ManusSessionEventRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ManusSessionEventService {

    private final ManusSessionEventRepository manusSessionEventRepository;

    public ManusSessionEventService(ManusSessionEventRepository manusSessionEventRepository) {
        this.manusSessionEventRepository = manusSessionEventRepository;
    }

    public ManusSessionEvent recordEvent(String sessionId, String eventType, String title, String content) {
        ManusSessionEventEntity entity = new ManusSessionEventEntity();
        entity.setSessionId(sessionId);
        entity.setEventType(eventType);
        entity.setTitle(title);
        entity.setContent(sanitize(content));
        ManusSessionEventEntity saved = manusSessionEventRepository.save(entity);
        return toRecord(saved);
    }

    public List<ManusSessionEvent> listEvents(String sessionId) {
        return manusSessionEventRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toRecord)
                .toList();
    }

    private ManusSessionEvent toRecord(ManusSessionEventEntity entity) {
        return new ManusSessionEvent(
                String.valueOf(entity.getId()),
                entity.getSessionId(),
                entity.getEventType(),
                entity.getTitle(),
                entity.getContent(),
                entity.getCreatedAt()
        );
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").trim();
    }

    public record ManusSessionEvent(
            String id,
            String sessionId,
            String eventType,
            String title,
            String content,
            LocalDateTime createdAt
    ) {
    }
}
