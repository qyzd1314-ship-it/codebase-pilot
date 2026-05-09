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
public class ReviewerDecisionDto {

    private Boolean passed;

    private String reason;

    private List<String> unsupportedClaims;

    private String risk;

    private String suggestedAction;
}
