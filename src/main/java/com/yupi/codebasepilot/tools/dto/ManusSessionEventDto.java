package com.yupi.codebasepilot.tools.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ManusSessionEventDto {

    private String id;

    private String sessionId;

    private String eventType;

    private String title;

    private String content;

    private LocalDateTime createdAt;
}
