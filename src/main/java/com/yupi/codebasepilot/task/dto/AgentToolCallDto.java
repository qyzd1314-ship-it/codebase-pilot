package com.yupi.codebasepilot.task.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class AgentToolCallDto {

    Long id;
    Long stepId;
    String toolName;
    String toolCategory;
    String riskLevel;
    String requestPayload;
    String responsePayload;
    Boolean success;
    String errorMessage;
    LocalDateTime startedAt;
    LocalDateTime finishedAt;
    LocalDateTime createdAt;
}
