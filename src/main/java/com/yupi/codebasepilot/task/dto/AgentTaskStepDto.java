package com.yupi.codebasepilot.task.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class AgentTaskStepDto {

    Long id;
    Integer stepSeq;
    String stepTitle;
    String stepType;
    String assignedAgent;
    String toolName;
    String toolCategory;
    String riskLevel;
    String status;
    String plannerOutput;
    String executorInput;
    String executorOutput;
    List<EvidenceRefDto> evidenceRefs;
    Boolean requiresApproval;
    Integer retryCount;
    Integer maxRetry;
    LocalDateTime startedAt;
    LocalDateTime finishedAt;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
