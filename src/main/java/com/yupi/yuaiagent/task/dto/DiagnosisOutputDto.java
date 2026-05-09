package com.yupi.yuaiagent.task.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisOutputDto {

    private String summary;

    private List<DiagnosisHypothesisDto> hypotheses;

    private Boolean needMoreSearch;

    private List<String> missingInfo;
}
