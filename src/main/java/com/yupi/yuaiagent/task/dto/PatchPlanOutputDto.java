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
public class PatchPlanOutputDto {

    private List<String> filesToChange;

    private String patchPlan;

    private String diffPreview;

    private List<String> testSuggestions;

    private List<String> risks;

    private Boolean needMoreInfo;

    private List<String> missingInfo;
}
