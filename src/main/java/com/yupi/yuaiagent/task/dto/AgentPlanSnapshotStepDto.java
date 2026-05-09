package com.yupi.yuaiagent.task.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentPlanSnapshotStepDto {

    Integer stepSeq;
    String stepTitle;
    String stepType;
    String toolName;
    String toolCategory;
    String riskLevel;
}
