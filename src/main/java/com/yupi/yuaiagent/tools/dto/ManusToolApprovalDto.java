package com.yupi.yuaiagent.tools.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ManusToolApprovalDto {

    String sessionId;
    String toolName;
    String status;
    String reason;
    String approvedBy;
    String decisionNote;
    LocalDateTime createdAt;
    LocalDateTime decidedAt;
}
