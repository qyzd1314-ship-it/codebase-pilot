package com.yupi.codebasepilot.eval.dto;

import com.yupi.codebasepilot.task.dto.EvidenceRefDto;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class EvalCaseResultDto {

    String caseId;

    String repoId;

    String question;

    String caseType;

    String difficulty;

    String strategy;

    List<String> expectedFiles;

    List<String> expectedKeywords;

    String expectedRootCause;

    List<String> retrievedFiles;

    List<EvalRetrievedChunkDto> retrievedChunks;

    List<String> matchedFilesAt5;

    List<String> matchedFilesAt10;

    boolean retrievalHit;

    boolean expectedFilesHit;

    double recallAt5;

    double recallAt10;

    double evidenceGroundingRate;

    boolean rootCauseKeywordHit;

    boolean diagnosisHasEvidence;

    String diagnosisSummary;

    Map<String, Object> diagnosisOutput;

    List<EvidenceRefDto> evidenceRefs;

    Boolean needMoreSearch;

    Boolean reviewerPassed;

    String reviewerAction;

    String reviewerReason;

    Map<String, Object> reviewerOutput;

    boolean jsonParseSuccess;

    boolean llmSuccess;

    String llmErrorMessage;

    Integer promptTokens;

    Integer completionTokens;

    Integer totalTokens;

    long latencyMs;

    List<String> filesToChange;

    boolean patchGenerated;

    Boolean patchNeedMoreInfo;

    Map<String, Object> patchOutput;

    String errorMessage;
}
