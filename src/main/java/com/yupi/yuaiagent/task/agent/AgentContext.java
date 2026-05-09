package com.yupi.yuaiagent.task.agent;

import com.yupi.yuaiagent.task.dto.EvidenceRefDto;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class AgentContext {

    Long taskId;
    Long stepId;
    String repoId;
    String businessType;
    String userGoal;
    List<AgentStepSummary> previousSteps;
    List<EvidenceRefDto> evidenceRefs;
    Map<String, Object> memory;
}
