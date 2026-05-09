package com.yupi.yuaiagent.task.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentPlanDiffItemDto {

    String changeType;
    Integer stepSeq;
    String previousLabel;
    String currentLabel;
    String reason;
}
