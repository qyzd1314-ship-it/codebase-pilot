package com.yupi.codebasepilot.eval.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class EvalReportDto {

    int totalCases;

    int totalRuns;

    double recallAt5;

    double recallAt10;

    double evidenceGroundingRate;

    double jsonParseSuccessRate;

    double averageLatencyMs;

    double averageTokenCost;

    String reportPath;

    List<EvalStrategyReportDto> strategyReports;

    List<EvalCaseResultDto> perCaseResults;
}
