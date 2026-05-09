package com.yupi.codebasepilot.tools.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ManusSessionDto {

    private String sessionId;

    private String displayName;

    private List<String> tags;

    private String status;

    private Boolean pinned;

    private String workspacePath;

    private Integer messageCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime lastActiveAt;
}
