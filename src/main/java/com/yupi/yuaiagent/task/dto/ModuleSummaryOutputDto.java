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
public class ModuleSummaryOutputDto {

    private String intent;

    private String targetModule;

    private String subType;

    private String outputSchema;

    private String deliveryMode;

    private String summary;

    private List<CodeModuleDto> modules;

    private List<CodeFlowStepDto> flowSteps;

    private List<java.util.Map<String, Object>> operations;

    private List<java.util.Map<String, Object>> callChain;

    private List<String> architectureNotes;

    private List<String> riskNotes;

    private List<String> notesAndRisks;

    private Boolean needMoreSearch;

    private Boolean partial;

    private List<String> confirmedScope;

    private List<String> missingInfo;

    private List<String> suggestedFollowUpQueries;
}
