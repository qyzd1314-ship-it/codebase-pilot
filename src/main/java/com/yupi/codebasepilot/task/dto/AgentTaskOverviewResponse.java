package com.yupi.codebasepilot.task.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AgentTaskOverviewResponse {

    Integer activeTaskCount;
    Integer attentionTaskCount;
    Integer deliveredTaskCount;
    List<AgentTaskResponse> prioritizedTasks;
}
