package com.yupi.codebasepilot.task.agent;

import com.yupi.codebasepilot.task.dto.EvidenceRefDto;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record StoredAgentResult(
        String summary,
        Map<String, Object> structuredOutput,
        Double confidence,
        String nextAction,
        String failureReason,
        List<EvidenceRefDto> evidenceRefs
) {
}
