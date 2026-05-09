package com.yupi.yuaiagent.tools.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ManusToolCallDto {

    private String id;

    private String sessionId;

    private String toolName;

    private String toolCategory;

    private String riskLevel;

    private String requestPayload;

    private String responsePayload;

    private Boolean success;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
