package com.yupi.yuaiagent.task.agent;

import com.yupi.yuaiagent.task.dto.EvidenceRefDto;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class AgentStepSummary {

    Long stepId;
    Integer stepSeq;
    String stepTitle;
    String assignedAgent;
    String summary;
    Map<String, Object> structuredOutput;
    List<EvidenceRefDto> evidenceRefs;
    Double confidence;
    String nextAction;
    String failureReason;
}
