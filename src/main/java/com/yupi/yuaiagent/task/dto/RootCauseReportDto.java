package com.yupi.yuaiagent.task.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class RootCauseReportDto {

    String summary;
    List<RootCauseHypothesisDto> hypotheses;
    String risk;
    List<String> nextSteps;
}
