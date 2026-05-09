package com.yupi.codebasepilot.eval.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class EvalStrategyReportDto {

    String strategy;

    int totalCases;

    double recallAt5;

    double recallAt10;

    double evidenceGroundingRate;

    double jsonParseSuccessRate;

    double reviewerPassRate;

    double llmSuccessRate;

    double averageLatencyMs;

    double averageTokenCost;
}
