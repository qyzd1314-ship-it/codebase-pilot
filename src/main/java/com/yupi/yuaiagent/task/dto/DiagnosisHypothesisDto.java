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
public class DiagnosisHypothesisDto {

    private String cause;

    private List<String> evidence;

    private Double confidence;

    private String risk;
}
