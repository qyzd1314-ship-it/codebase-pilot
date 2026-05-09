package com.yupi.codebasepilot.task.service;

import com.yupi.codebasepilot.task.dto.AgentTaskEventDto;
import com.yupi.codebasepilot.task.entity.AgentTaskEvent;
import com.yupi.codebasepilot.task.repository.AgentTaskEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class AgentTaskEventService {

    private final AgentTaskEventRepository agentTaskEventRepository;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitterMap = new ConcurrentHashMap<>();

    public AgentTaskEventService(AgentTaskEventRepository agentTaskEventRepository) {
        this.agentTaskEventRepository = agentTaskEventRepository;
    }

    public AgentTaskEvent appendEvent(Long taskId, Long stepId, String eventType, String eventLevel,
                                      String eventContent, String metadataJson) {
        AgentTaskEvent event = new AgentTaskEvent();
        event.setTaskId(taskId);
        event.setStepId(stepId);
        event.setEventType(eventType);
        event.setEventLevel(eventLevel);
        event.setEventContent(eventContent);
        event.setMetadataJson(metadataJson);
        AgentTaskEvent savedEvent = agentTaskEventRepository.save(event);
        broadcast(savedEvent);
        return savedEvent;
    }

    public List<AgentTaskEvent> listEvents(Long taskId) {
        return agentTaskEventRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
    }

    public List<AgentTaskEventDto> listEventDtos(Long taskId) {
        return listEvents(taskId).stream().map(this::toDto).toList();
    }

    public SseEmitter createEmitter(Long taskId) {
        SseEmitter emitter = new SseEmitter(300000L);
        emitterMap.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        emitter.onError(ex -> removeEmitter(taskId, emitter));

        List<AgentTaskEvent> history = listEvents(taskId);
        history.forEach(event -> sendEvent(emitter, event));
        return emitter;
    }

    private void broadcast(AgentTaskEvent event) {
        List<SseEmitter> emitters = emitterMap.get(event.getTaskId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                sendEvent(emitter, event);
            } catch (Exception e) {
                removeEmitter(event.getTaskId(), emitter);
            }
        }
    }

    private void sendEvent(SseEmitter emitter, AgentTaskEvent event) {
        try {
            emitter.send(SseEmitter.event().data(toDto(event)));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    private void removeEmitter(Long taskId, SseEmitter emitter) {
        List<SseEmitter> emitters = emitterMap.get(taskId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emitterMap.remove(taskId);
        }
    }

    private AgentTaskEventDto toDto(AgentTaskEvent event) {
        return AgentTaskEventDto.builder()
                .id(event.getId())
                .stepId(event.getStepId())
                .eventType(event.getEventType())
                .eventLevel(event.getEventLevel())
                .eventContent(event.getEventContent())
                .metadataJson(event.getMetadataJson())
                .createdAt(event.getCreatedAt())
                .build();
    }
}
