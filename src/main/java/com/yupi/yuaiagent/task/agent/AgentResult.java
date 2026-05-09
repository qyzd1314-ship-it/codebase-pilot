package com.yupi.yuaiagent.task.agent;

import com.yupi.yuaiagent.task.dto.EvidenceRefDto;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class AgentResult {

    boolean success;
    String summary;
    Map<String, Object> structuredOutput;
    List<EvidenceRefDto> evidenceRefs;
    Double confidence;
    NextAction nextAction;
    String failureReason;
}
