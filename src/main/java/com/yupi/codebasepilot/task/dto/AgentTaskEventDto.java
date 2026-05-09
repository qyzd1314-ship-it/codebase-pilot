package com.yupi.codebasepilot.task.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class AgentTaskEventDto {

    Long id;
    Long stepId;
    String eventType;
    String eventLevel;
    String eventContent;
    String metadataJson;
    LocalDateTime createdAt;
}
