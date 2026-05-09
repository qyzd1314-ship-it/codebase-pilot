package com.yupi.yuaiagent.task.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class AgentApprovalDto {

    Long id;
    String approvalType;
    String title;
    String reason;
    String status;
    String decisionBy;
    String decisionNote;
    LocalDateTime createdAt;
    LocalDateTime decidedAt;
}
