package com.yupi.yuaiagent.task.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class RootCauseHypothesisDto {

    String cause;
    List<String> evidence;
    Double confidence;
}
