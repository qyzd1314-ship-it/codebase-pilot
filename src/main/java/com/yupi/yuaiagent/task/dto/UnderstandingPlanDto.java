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
public class UnderstandingPlanDto {

    private String intent;

    private String targetModule;

    private List<String> targetKeywords;

    private List<String> expectedLayers;

    private String outputSchema;

    private String reason;
}
