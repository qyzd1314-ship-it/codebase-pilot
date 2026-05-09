package com.yupi.codebasepilot.eval;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.codebasepilot.eval.dto.EvalCaseDto;
import com.yupi.codebasepilot.eval.dto.EvalCaseResultDto;
import com.yupi.codebasepilot.eval.dto.EvalReportDto;
import com.yupi.codebasepilot.eval.dto.EvalRetrievedChunkDto;
import com.yupi.codebasepilot.eval.dto.EvalStrategy;
import com.yupi.codebasepilot.eval.dto.EvalStrategyReportDto;
import com.yupi.codebasepilot.llm.LlmService;
import com.yupi.codebasepilot.llm.dto.LlmCallStatus;
import com.yupi.codebasepilot.llm.dto.LlmRequest;
import com.yupi.codebasepilot.llm.dto.LlmStructuredResponse;
import com.yupi.codebasepilot.repo.dto.CodeSearchRequest;
import com.yupi.codebasepilot.repo.dto.CodeSearchResponse;
import com.yupi.codebasepilot.repo.dto.CodeSearchResultDto;
import com.yupi.codebasepilot.repo.enums.CodeSearchMode;
import com.yupi.codebasepilot.repo.service.CodeSearchService;
import com.yupi.codebasepilot.task.agent.AgentContext;
import com.yupi.codebasepilot.task.agent.AgentResult;
import com.yupi.codebasepilot.task.agent.AgentStepSummary;
import com.yupi.codebasepilot.task.agent.NextAction;
import com.yupi.codebasepilot.task.agent.impl.DiagnosisAgent;
import com.yupi.codebasepilot.task.agent.impl.PatchAgent;
import com.yupi.codebasepilot.task.agent.impl.ReviewerAgent;
import com.yupi.codebasepilot.task.dto.DiagnosisHypothesisDto;
import com.yupi.codebasepilot.task.dto.DiagnosisOutputDto;
import com.yupi.codebasepilot.task.dto.EvidenceRefDto;
import com.yupi.codebasepilot.task.dto.PatchPlanOutputDto;
import com.yupi.codebasepilot.task.dto.ReviewerDecisionDto;
import com.yupi.codebasepilot.task.entity.AgentTask;
import com.yupi.codebasepilot.task.enums.AgentTaskStatus;
import com.yupi.codebasepilot.task.repository.AgentTaskRepository;
import com.yupi.codebasepilot.task.service.AgentArtifactService;
import com.yupi.codebasepilot.task.service.AgentTaskWorkspaceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EvalRunnerService {

    private static final int TOP_K_5 = 5;
    private static final int TOP_K_10 = 10;
    private static final String LLM_ONLY_SYSTEM_PROMPT = """
            You are evaluating a bug diagnosis request without repository evidence.
            Produce a best-effort diagnosis in JSON only.
            JSON schema:
            {
              "summary": "...",
              "hypotheses": [
                {
                  "cause": "...",
                  "evidence": [],
                  "confidence": 0.5,
                  "risk": "..."
                }
              ],
              "needMoreSearch": true,
              "missingInfo": []
            }
            """;

    private final ObjectMapper objectMapper;
    private final CodeSearchService codeSearchService;
    private final ResourceLoader resourceLoader;
    private final LlmService llmService;
    private final AgentTaskRepository agentTaskRepository;
    private final AgentTaskWorkspaceService agentTaskWorkspaceService;
    private final AgentArtifactService agentArtifactService;
    private final String evalCasesResourceLocation;
    private final String evalReportPath;

    public EvalRunnerService(ObjectMapper objectMapper,
                             CodeSearchService codeSearchService,
                             ResourceLoader resourceLoader,
                             LlmService llmService,
                             AgentTaskRepository agentTaskRepository,
                             AgentTaskWorkspaceService agentTaskWorkspaceService,
                             AgentArtifactService agentArtifactService,
                             @Value("${app.eval.cases-resource:classpath:eval_cases.json}") String evalCasesResourceLocation,
                             @Value("${app.eval.report-path:tmp/eval/eval_report.json}") String evalReportPath) {
        this.objectMapper = objectMapper;
        this.codeSearchService = codeSearchService;
        this.resourceLoader = resourceLoader;
        this.llmService = llmService;
        this.agentTaskRepository = agentTaskRepository;
        this.agentTaskWorkspaceService = agentTaskWorkspaceService;
        this.agentArtifactService = agentArtifactService;
        this.evalCasesResourceLocation = evalCasesResourceLocation;
        this.evalReportPath = evalReportPath;
    }

    public EvalReportDto runDefaultEval() {
        List<EvalCaseDto> cases = loadCases(evalCasesResourceLocation);
        List<EvalStrategy> strategies = Arrays.asList(EvalStrategy.values());
        List<EvalCaseResultDto> results = new ArrayList<>();

        for (EvalCaseDto evalCase : cases) {
            for (EvalStrategy strategy : strategies) {
                results.add(evaluateCase(evalCase, strategy));
            }
        }

        List<EvalStrategyReportDto> strategyReports = strategies.stream()
                .map(strategy -> buildStrategyReport(strategy, results))
                .toList();

        EvalReportDto report = EvalReportDto.builder()
                .totalCases(cases.size())
                .totalRuns(results.size())
                .recallAt5(average(results, EvalCaseResultDto::getRecallAt5))
                .recallAt10(average(results, EvalCaseResultDto::getRecallAt10))
                .evidenceGroundingRate(average(results, EvalCaseResultDto::getEvidenceGroundingRate))
                .jsonParseSuccessRate(rate(results, EvalCaseResultDto::isJsonParseSuccess))
                .averageLatencyMs(averageLong(results, EvalCaseResultDto::getLatencyMs))
                .averageTokenCost(averageTokenCost(results))
                .reportPath(evalReportPath.replace("\\", "/"))
                .strategyReports(strategyReports)
                .perCaseResults(results)
                .build();
        writeReportFile(report);
        return report;
    }

    private EvalCaseResultDto evaluateCase(EvalCaseDto evalCase, EvalStrategy strategy) {
        long startedAt = System.nanoTime();
        EvalLlmServiceCollector collector = new EvalLlmServiceCollector(llmService);
        try {
            SearchEvalData searchData = strategy == EvalStrategy.LLM_ONLY
                    ? SearchEvalData.empty()
                    : runSearch(evalCase, resolveSearchMode(strategy));

            AgentResult diagnosisResult = switch (strategy) {
                case KEYWORD_ONLY, VECTOR_ONLY, HYBRID -> null;
                case LLM_ONLY -> runLlmOnlyDiagnosis(evalCase, collector);
                case RAG_ONLY, AGENT_RAG_REVIEWER, AGENT_RAG_REVIEWER_PATCH -> runDiagnosis(evalCase, searchData.evidenceRefs(), collector);
            };

            AgentResult reviewerResult = null;
            if (strategy == EvalStrategy.AGENT_RAG_REVIEWER || strategy == EvalStrategy.AGENT_RAG_REVIEWER_PATCH) {
                reviewerResult = runReviewer(evalCase, searchData.evidenceRefs(), diagnosisResult, collector);
            }

            AgentResult patchResult = null;
            if (strategy == EvalStrategy.AGENT_RAG_REVIEWER_PATCH) {
                patchResult = runPatch(evalCase, searchData.evidenceRefs(), diagnosisResult, reviewerResult, collector);
            }

            long latencyMs = toLatencyMs(startedAt);
            return buildCaseResult(evalCase, strategy, searchData, diagnosisResult, reviewerResult, patchResult, collector, latencyMs, null);
        } catch (Exception e) {
            long latencyMs = toLatencyMs(startedAt);
            return buildCaseResult(evalCase, strategy, SearchEvalData.empty(), null, null, null, collector, latencyMs, e.getMessage());
        }
    }

    private SearchEvalData runSearch(EvalCaseDto evalCase, CodeSearchMode searchMode) {
        CodeSearchRequest request = new CodeSearchRequest();
        request.setQuery(evalCase.getQuestion());
        request.setTopK(TOP_K_10);
        request.setSearchMode(searchMode.name());
        CodeSearchResponse response = codeSearchService.search(evalCase.getRepoId(), request);
        List<CodeSearchResultDto> results = response == null || response.getResults() == null ? List.of() : response.getResults();
        List<String> retrievedFiles = results.stream()
                .map(CodeSearchResultDto::getFilePath)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        List<EvalRetrievedChunkDto> retrievedChunks = results.stream()
                .map(this::toRetrievedChunk)
                .toList();
        List<EvidenceRefDto> evidenceRefs = results.stream()
                .map(this::toEvidenceRef)
                .toList();
        List<String> matchedAt5 = matchExpectedFiles(safeList(evalCase.getExpectedFiles()), retrievedFiles.stream().limit(TOP_K_5).toList());
        List<String> matchedAt10 = matchExpectedFiles(safeList(evalCase.getExpectedFiles()), retrievedFiles);
        int expectedSize = safeList(evalCase.getExpectedFiles()).size();
        double recallAt5 = expectedSize == 0 ? 0D : round((double) matchedAt5.size() / expectedSize);
        double recallAt10 = expectedSize == 0 ? 0D : round((double) matchedAt10.size() / expectedSize);
        return new SearchEvalData(retrievedFiles, retrievedChunks, evidenceRefs, matchedAt5, matchedAt10, recallAt5, recallAt10);
    }

    private AgentResult runLlmOnlyDiagnosis(EvalCaseDto evalCase, EvalLlmServiceCollector collector) {
        LlmStructuredResponse<DiagnosisOutputDto> response = collector.chatForObject(
                LlmRequest.builder()
                        .systemPrompt(LLM_ONLY_SYSTEM_PROMPT)
                        .userPrompt("""
                                User question:
                                %s

                                Please answer with a best-effort diagnosis even though repository evidence is unavailable.
                                Mark needMoreSearch=true when the answer is uncertain.
                                """.formatted(defaultString(evalCase.getQuestion(), "(empty)")))
                        .responseFormatHint("Respond with JSON only.")
                        .scene("EVAL_LLM_ONLY")
                        .metadata(Map.of(
                                "caseId", defaultString(evalCase.getId(), "unknown"),
                                "repoId", defaultString(evalCase.getRepoId(), "unknown")
                        ))
                        .build(),
                DiagnosisOutputDto.class
        );
        if (!response.isSuccess()) {
            return AgentResult.builder()
                    .success(false)
                    .summary(defaultString(response.getContent(), "LLM-only diagnosis failed."))
                    .structuredOutput(Map.of(
                            "summary", "LLM-only diagnosis failed.",
                            "hypotheses", List.of(),
                            "needMoreSearch", true,
                            "missingInfo", List.of(defaultString(response.getErrorMessage(), "unknown error"))
                    ))
                    .evidenceRefs(List.of())
                    .confidence(0.1D)
                    .nextAction(NextAction.NEED_HUMAN_APPROVAL)
                    .failureReason(defaultString(response.getErrorMessage(), "LLM-only diagnosis failed."))
                    .build();
        }
        DiagnosisOutputDto output = normalizeDiagnosisOutput(response.getData(), response.getContent());
        return AgentResult.builder()
                .success(!Boolean.TRUE.equals(output.getNeedMoreSearch()))
                .summary(defaultString(output.getSummary(), "LLM-only diagnosis completed."))
                .structuredOutput(objectMapper.convertValue(output, new TypeReference<>() {
                }))
                .evidenceRefs(List.of())
                .confidence(averageHypothesisConfidence(output.getHypotheses()))
                .nextAction(Boolean.TRUE.equals(output.getNeedMoreSearch()) ? NextAction.REPLAN : NextAction.CONTINUE)
                .failureReason(Boolean.TRUE.equals(output.getNeedMoreSearch()) ? "LLM-only diagnosis still needs more repository evidence." : null)
                .build();
    }

    private AgentResult runDiagnosis(EvalCaseDto evalCase, List<EvidenceRefDto> evidenceRefs, EvalLlmServiceCollector collector) {
        DiagnosisAgent diagnosisAgent = new DiagnosisAgent(collector, objectMapper);
        AgentContext context = AgentContext.builder()
                .taskId(-1L)
                .stepId(3L)
                .repoId(evalCase.getRepoId())
                .businessType(evalCase.getCaseType())
                .userGoal(evalCase.getQuestion())
                .previousSteps(List.of(buildCodeSearchStepSummary(evidenceRefs)))
                .evidenceRefs(evidenceRefs)
                .memory(Map.of("evalStrategy", "RAG"))
                .build();
        return diagnosisAgent.run(context);
    }

    private CodeSearchMode resolveSearchMode(EvalStrategy strategy) {
        return switch (strategy) {
            case KEYWORD_ONLY -> CodeSearchMode.KEYWORD_ONLY;
            case VECTOR_ONLY -> CodeSearchMode.VECTOR_ONLY;
            default -> CodeSearchMode.HYBRID;
        };
    }

    private AgentResult runReviewer(EvalCaseDto evalCase,
                                    List<EvidenceRefDto> evidenceRefs,
                                    AgentResult diagnosisResult,
                                    EvalLlmServiceCollector collector) {
        ReviewerAgent reviewerAgent = new ReviewerAgent(collector, objectMapper);
        AgentContext context = AgentContext.builder()
                .taskId(-1L)
                .stepId(4L)
                .repoId(evalCase.getRepoId())
                .businessType(evalCase.getCaseType())
                .userGoal(evalCase.getQuestion())
                .previousSteps(List.of(
                        buildCodeSearchStepSummary(evidenceRefs),
                        buildAgentStepSummary(3L, 3, "鍒嗘瀽鍙兘鍘熷�?, "DiagnosisAgent", diagnosisResult)
                ))
                .evidenceRefs(evidenceRefs)
                .memory(Map.of("evalStrategy", "AGENT_RAG_REVIEWER"))
                .build();
        return reviewerAgent.run(context);
    }

    private AgentResult runPatch(EvalCaseDto evalCase,
                                 List<EvidenceRefDto> evidenceRefs,
                                 AgentResult diagnosisResult,
                                 AgentResult reviewerResult,
                                 EvalLlmServiceCollector collector) {
        AgentTask task = createEvalTask(evalCase);
        try {
            PatchAgent patchAgent = new PatchAgent(collector, objectMapper, agentTaskRepository, agentTaskWorkspaceService, agentArtifactService);
            List<AgentStepSummary> previousSteps = new ArrayList<>();
            previousSteps.add(buildCodeSearchStepSummary(evidenceRefs));
            previousSteps.add(buildAgentStepSummary(3L, 3, "Analyze possible causes", "DiagnosisAgent", diagnosisResult));
            if (reviewerResult != null) {
                previousSteps.add(buildAgentStepSummary(4L, 4, "Review evidence and diagnosis", "ReviewerAgent", reviewerResult));
            }
            AgentContext context = AgentContext.builder()
                    .taskId(task.getId())
                    .stepId(5L)
                    .repoId(evalCase.getRepoId())
                    .businessType("PATCH_SUGGESTION")
                    .userGoal(evalCase.getQuestion())
                    .previousSteps(previousSteps)
                    .evidenceRefs(evidenceRefs)
                    .memory(Map.of("evalStrategy", "AGENT_RAG_REVIEWER_PATCH"))
                    .build();
            return patchAgent.run(context);
        } finally {
            agentTaskRepository.deleteById(task.getId());
        }
    }

    private EvalCaseResultDto buildCaseResult(EvalCaseDto evalCase,
                                              EvalStrategy strategy,
                                              SearchEvalData searchData,
                                              AgentResult diagnosisResult,
                                              AgentResult reviewerResult,
                                              AgentResult patchResult,
                                              EvalLlmServiceCollector collector,
                                              long latencyMs,
                                              String errorMessage) {
        DiagnosisOutputDto diagnosisOutput = readDiagnosisOutput(diagnosisResult);
        PatchPlanOutputDto patchOutput = readPatchOutput(patchResult);
        boolean diagnosisHasEvidence = diagnosisOutput.getHypotheses() != null
                && diagnosisOutput.getHypotheses().stream().anyMatch(hypothesis -> hypothesis.getEvidence() != null && !hypothesis.getEvidence().isEmpty());
        boolean rootCauseKeywordHit = rootCauseKeywordHit(evalCase, diagnosisOutput);
        double evidenceGroundingRate = computeEvidenceGroundingRate(diagnosisOutput, diagnosisResult == null ? List.of() : safeEvidence(diagnosisResult.getEvidenceRefs()));
        String reviewerAction = reviewerResult == null || reviewerResult.getNextAction() == null ? null : reviewerResult.getNextAction().name();
        String reviewerReason = reviewerResult == null ? null : defaultString(reviewerResult.getSummary(), reviewerResult.getFailureReason());
        boolean reviewerPassed = reviewerResult != null && reviewerResult.getNextAction() == NextAction.DELIVER;
        return EvalCaseResultDto.builder()
                .caseId(evalCase.getId())
                .repoId(evalCase.getRepoId())
                .question(evalCase.getQuestion())
                .caseType(evalCase.getCaseType())
                .difficulty(evalCase.getDifficulty())
                .strategy(strategy.name())
                .expectedFiles(safeList(evalCase.getExpectedFiles()))
                .expectedKeywords(safeList(evalCase.getExpectedKeywords()))
                .expectedRootCause(evalCase.getExpectedRootCause())
                .retrievedFiles(searchData.retrievedFiles())
                .retrievedChunks(searchData.retrievedChunks())
                .matchedFilesAt5(searchData.matchedFilesAt5())
                .matchedFilesAt10(searchData.matchedFilesAt10())
                .retrievalHit(!searchData.retrievedFiles().isEmpty())
                .expectedFilesHit(!searchData.matchedFilesAt10().isEmpty())
                .recallAt5(searchData.recallAt5())
                .recallAt10(searchData.recallAt10())
                .evidenceGroundingRate(evidenceGroundingRate)
                .rootCauseKeywordHit(rootCauseKeywordHit)
                .diagnosisHasEvidence(diagnosisHasEvidence)
                .diagnosisSummary(diagnosisResult == null ? null : diagnosisResult.getSummary())
                .diagnosisOutput(diagnosisResult == null || diagnosisResult.getStructuredOutput() == null
                        ? Map.of()
                        : diagnosisResult.getStructuredOutput())
                .evidenceRefs(diagnosisResult == null ? List.of() : safeEvidence(diagnosisResult.getEvidenceRefs()))
                .needMoreSearch(diagnosisOutput.getNeedMoreSearch())
                .reviewerPassed(reviewerResult == null ? null : reviewerPassed)
                .reviewerAction(reviewerAction)
                .reviewerReason(reviewerReason)
                .reviewerOutput(reviewerResult == null || reviewerResult.getStructuredOutput() == null
                        ? Map.of()
                        : reviewerResult.getStructuredOutput())
                .jsonParseSuccess(collector.isJsonParseSuccess())
                .llmSuccess(collector.isLlmSuccess())
                .llmErrorMessage(collector.errorMessage())
                .promptTokens(collector.promptTokens())
                .completionTokens(collector.completionTokens())
                .totalTokens(collector.totalTokens())
                .latencyMs(latencyMs)
                .filesToChange(patchOutput.getFilesToChange() == null ? List.of() : patchOutput.getFilesToChange())
                .patchGenerated(patchResult != null && patchResult.isSuccess())
                .patchNeedMoreInfo(patchOutput.getNeedMoreInfo())
                .patchOutput(patchResult == null || patchResult.getStructuredOutput() == null
                        ? Map.of()
                        : patchResult.getStructuredOutput())
                .errorMessage(errorMessage)
                .build();
    }

    private EvalStrategyReportDto buildStrategyReport(EvalStrategy strategy, List<EvalCaseResultDto> results) {
        List<EvalCaseResultDto> scopedResults = results.stream()
                .filter(result -> strategy.name().equals(result.getStrategy()))
                .toList();
        return EvalStrategyReportDto.builder()
                .strategy(strategy.name())
                .totalCases(scopedResults.size())
                .recallAt5(average(scopedResults, EvalCaseResultDto::getRecallAt5))
                .recallAt10(average(scopedResults, EvalCaseResultDto::getRecallAt10))
                .evidenceGroundingRate(average(scopedResults, EvalCaseResultDto::getEvidenceGroundingRate))
                .jsonParseSuccessRate(rate(scopedResults, EvalCaseResultDto::isJsonParseSuccess))
                .reviewerPassRate(rate(scopedResults, result -> Boolean.TRUE.equals(result.getReviewerPassed())))
                .llmSuccessRate(rate(scopedResults, EvalCaseResultDto::isLlmSuccess))
                .averageLatencyMs(averageLong(scopedResults, EvalCaseResultDto::getLatencyMs))
                .averageTokenCost(averageTokenCost(scopedResults))
                .build();
    }

    private List<EvalCaseDto> loadCases(String resourceLocation) {
        Resource resource = resourceLoader.getResource(resourceLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("Eval cases resource not found: " + resourceLocation);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<List<EvalCaseDto>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read eval cases from " + resourceLocation, e);
        }
    }

    private List<String> matchExpectedFiles(List<String> expectedFiles, List<String> retrievedFiles) {
        if (expectedFiles.isEmpty() || retrievedFiles.isEmpty()) {
            return List.of();
        }
        Set<String> matched = new LinkedHashSet<>();
        for (String expectedFile : expectedFiles) {
            String normalizedExpected = normalizePath(expectedFile);
            for (String retrievedFile : retrievedFiles) {
                String normalizedRetrieved = normalizePath(retrievedFile);
                if (normalizedRetrieved.endsWith(normalizedExpected) || normalizedRetrieved.contains(normalizedExpected)) {
                    matched.add(expectedFile);
                    break;
                }
            }
        }
        return matched.stream().toList();
    }

    private EvidenceRefDto toEvidenceRef(CodeSearchResultDto result) {
        return EvidenceRefDto.builder()
                .repoId(null)
                .chunkId(result.getChunkId())
                .filePath(result.getFilePath())
                .startLine(result.getStartLine())
                .endLine(result.getEndLine())
                .score(result.getScore())
                .reason(result.getReason())
                .codePreview(result.getContentPreview())
                .build();
    }

    private EvalRetrievedChunkDto toRetrievedChunk(CodeSearchResultDto result) {
        return EvalRetrievedChunkDto.builder()
                .chunkId(result.getChunkId())
                .filePath(result.getFilePath())
                .symbolName(result.getSymbolName())
                .startLine(result.getStartLine())
                .endLine(result.getEndLine())
                .score(result.getScore())
                .reason(result.getReason())
                .matchSource(result.getMatchSource())
                .contentPreview(result.getContentPreview())
                .build();
    }

    private AgentStepSummary buildCodeSearchStepSummary(List<EvidenceRefDto> evidenceRefs) {
        return AgentStepSummary.builder()
                .stepId(2L)
                .stepSeq(2)
                .stepTitle("Search related code")
                .assignedAgent("CodeSearchAgent")
                .summary("Eval search collected grounded code evidence.")
                .structuredOutput(Map.of("results", safeEvidence(evidenceRefs)))
                .evidenceRefs(safeEvidence(evidenceRefs))
                .confidence(evidenceRefs.isEmpty() ? 0.1D : 0.8D)
                .nextAction(NextAction.CONTINUE.name())
                .failureReason(null)
                .build();
    }

    private AgentStepSummary buildAgentStepSummary(Long stepId,
                                                   Integer stepSeq,
                                                   String stepTitle,
                                                   String agentName,
                                                   AgentResult result) {
        if (result == null) {
            return AgentStepSummary.builder()
                    .stepId(stepId)
                    .stepSeq(stepSeq)
                    .stepTitle(stepTitle)
                    .assignedAgent(agentName)
                    .summary("(missing)")
                    .structuredOutput(Map.of())
                    .evidenceRefs(List.of())
                    .confidence(0D)
                    .nextAction(NextAction.FAIL.name())
                    .failureReason("Missing agent result.")
                    .build();
        }
        return AgentStepSummary.builder()
                .stepId(stepId)
                .stepSeq(stepSeq)
                .stepTitle(stepTitle)
                .assignedAgent(agentName)
                .summary(result.getSummary())
                .structuredOutput(result.getStructuredOutput() == null ? Map.of() : result.getStructuredOutput())
                .evidenceRefs(safeEvidence(result.getEvidenceRefs()))
                .confidence(result.getConfidence())
                .nextAction(result.getNextAction() == null ? null : result.getNextAction().name())
                .failureReason(result.getFailureReason())
                .build();
    }

    private DiagnosisOutputDto readDiagnosisOutput(AgentResult diagnosisResult) {
        if (diagnosisResult == null || diagnosisResult.getStructuredOutput() == null) {
            return DiagnosisOutputDto.builder()
                    .summary("")
                    .hypotheses(List.of())
                    .needMoreSearch(null)
                    .missingInfo(List.of())
                    .build();
        }
        return normalizeDiagnosisOutput(
                objectMapper.convertValue(diagnosisResult.getStructuredOutput(), DiagnosisOutputDto.class),
                diagnosisResult.getSummary()
        );
    }

    private PatchPlanOutputDto readPatchOutput(AgentResult patchResult) {
        if (patchResult == null || patchResult.getStructuredOutput() == null) {
            return PatchPlanOutputDto.builder()
                    .filesToChange(List.of())
                    .patchPlan("")
                    .diffPreview("")
                    .testSuggestions(List.of())
                    .risks(List.of())
                    .needMoreInfo(null)
                    .missingInfo(List.of())
                    .build();
        }
        PatchPlanOutputDto output = objectMapper.convertValue(patchResult.getStructuredOutput(), PatchPlanOutputDto.class);
        if (output.getFilesToChange() == null) {
            output.setFilesToChange(List.of());
        }
        if (output.getTestSuggestions() == null) {
            output.setTestSuggestions(List.of());
        }
        if (output.getRisks() == null) {
            output.setRisks(List.of());
        }
        if (output.getMissingInfo() == null) {
            output.setMissingInfo(List.of());
        }
        return output;
    }

    private DiagnosisOutputDto normalizeDiagnosisOutput(DiagnosisOutputDto output, String rawContent) {
        DiagnosisOutputDto effective = output == null ? new DiagnosisOutputDto() : output;
        if (effective.getHypotheses() == null) {
            effective.setHypotheses(List.of());
        }
        if (effective.getMissingInfo() == null) {
            effective.setMissingInfo(List.of());
        }
        if (effective.getNeedMoreSearch() == null) {
            effective.setNeedMoreSearch(false);
        }
        if (StrUtil.isBlank(effective.getSummary()) && StrUtil.isNotBlank(rawContent)) {
            effective.setSummary(rawContent);
        }
        return effective;
    }

    private boolean rootCauseKeywordHit(EvalCaseDto evalCase, DiagnosisOutputDto output) {
        String diagnosisText = buildDiagnosisText(output);
        if (StrUtil.isBlank(diagnosisText)) {
            return false;
        }
        String normalizedDiagnosisText = diagnosisText.toLowerCase(Locale.ROOT);
        if (StrUtil.isNotBlank(evalCase.getExpectedRootCause())
                && normalizedDiagnosisText.contains(evalCase.getExpectedRootCause().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return safeList(evalCase.getExpectedKeywords()).stream()
                .map(keyword -> keyword == null ? "" : keyword.toLowerCase(Locale.ROOT))
                .filter(StrUtil::isNotBlank)
                .anyMatch(normalizedDiagnosisText::contains);
    }

    private String buildDiagnosisText(DiagnosisOutputDto output) {
        List<String> texts = new ArrayList<>();
        texts.add(defaultString(output.getSummary(), ""));
        if (output.getHypotheses() != null) {
            texts.addAll(output.getHypotheses().stream()
                    .map(DiagnosisHypothesisDto::getCause)
                    .filter(StrUtil::isNotBlank)
                    .toList());
        }
        return texts.stream()
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining(" "));
    }

    private double computeEvidenceGroundingRate(DiagnosisOutputDto output, List<EvidenceRefDto> evidenceRefs) {
        if (output.getHypotheses() == null || output.getHypotheses().isEmpty()) {
            return 0D;
        }
        Set<String> anchors = evidenceRefs.stream()
                .map(this::toEvidenceAnchor)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        List<String> citedEvidence = output.getHypotheses().stream()
                .filter(Objects::nonNull)
                .flatMap(hypothesis -> safeList(hypothesis.getEvidence()).stream())
                .filter(StrUtil::isNotBlank)
                .toList();
        if (citedEvidence.isEmpty()) {
            return 0D;
        }
        long grounded = citedEvidence.stream().filter(anchors::contains).count();
        return round((double) grounded / citedEvidence.size());
    }

    private String toEvidenceAnchor(EvidenceRefDto evidenceRef) {
        if (evidenceRef == null || StrUtil.isBlank(evidenceRef.getFilePath())
                || evidenceRef.getStartLine() == null || evidenceRef.getEndLine() == null) {
            return "";
        }
        return "%s:%d-%d".formatted(evidenceRef.getFilePath(), evidenceRef.getStartLine(), evidenceRef.getEndLine());
    }

    private double averageHypothesisConfidence(List<DiagnosisHypothesisDto> hypotheses) {
        if (hypotheses == null || hypotheses.isEmpty()) {
            return 0.35D;
        }
        return hypotheses.stream()
                .map(DiagnosisHypothesisDto::getConfidence)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.35D);
    }

    private AgentTask createEvalTask(EvalCaseDto evalCase) {
        AgentTask task = new AgentTask();
        task.setTaskNo("eval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        task.setTitle("Eval Patch Task - " + defaultString(evalCase.getId(), "unknown"));
        task.setGoal(defaultString(evalCase.getQuestion(), "Generate patch plan."));
        task.setTaskType("EVAL");
        task.setRepoId(evalCase.getRepoId());
        task.setBusinessType("PATCH_SUGGESTION");
        task.setStatus(AgentTaskStatus.PENDING);
        task.setAutoApproveLowRisk(true);
        task.setCurrentRound(0);
        task.setMaxRound(1);
        task.setReplanCount(0);
        task.setConsecutiveSameReasonReplanCount(0);
        task.setMaxConsecutiveSameReasonReplanCount(1);
        task.setStartedAt(LocalDateTime.now());
        agentTaskWorkspaceService.initializeWorkspace(task);
        return agentTaskRepository.save(task);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<EvidenceRefDto> safeEvidence(List<EvidenceRefDto> evidenceRefs) {
        return evidenceRefs == null ? List.of() : evidenceRefs;
    }

    private String normalizePath(String value) {
        return StrUtil.nullToEmpty(value)
                .replace("\\", "/")
                .toLowerCase(Locale.ROOT);
    }

    private long toLatencyMs(long startedAt) {
        return Math.round((System.nanoTime() - startedAt) / 1_000_000D);
    }

    private double average(List<EvalCaseResultDto> results, java.util.function.ToDoubleFunction<EvalCaseResultDto> extractor) {
        if (results.isEmpty()) {
            return 0D;
        }
        return round(results.stream().mapToDouble(extractor).average().orElse(0D));
    }

    private double averageLong(List<EvalCaseResultDto> results, java.util.function.ToLongFunction<EvalCaseResultDto> extractor) {
        if (results.isEmpty()) {
            return 0D;
        }
        return round(results.stream().mapToLong(extractor).average().orElse(0D));
    }

    private double rate(List<EvalCaseResultDto> results, java.util.function.Predicate<EvalCaseResultDto> predicate) {
        if (results.isEmpty()) {
            return 0D;
        }
        long count = results.stream().filter(predicate).count();
        return round((double) count / results.size());
    }

    private double averageTokenCost(List<EvalCaseResultDto> results) {
        if (results.isEmpty()) {
            return 0D;
        }
        return round(results.stream()
                .map(EvalCaseResultDto::getTotalTokens)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0D));
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private String defaultString(String value, String defaultValue) {
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    private void writeReportFile(EvalReportDto report) {
        Path reportFile = Path.of(evalReportPath);
        try {
            Files.createDirectories(reportFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write eval report to " + evalReportPath, e);
        }
    }

    private record SearchEvalData(List<String> retrievedFiles,
                                  List<EvalRetrievedChunkDto> retrievedChunks,
                                  List<EvidenceRefDto> evidenceRefs,
                                  List<String> matchedFilesAt5,
                                  List<String> matchedFilesAt10,
                                  double recallAt5,
                                  double recallAt10) {
        static SearchEvalData empty() {
            return new SearchEvalData(List.of(), List.of(), List.of(), List.of(), List.of(), 0D, 0D);
        }
    }

    private static final class EvalLlmServiceCollector implements LlmService {

        private final LlmService delegate;
        private final List<LlmStructuredResponse<?>> structuredResponses = new ArrayList<>();

        private EvalLlmServiceCollector(LlmService delegate) {
            this.delegate = delegate;
        }

        @Override
        public com.yupi.codebasepilot.llm.dto.LlmResponse chat(LlmRequest request) {
            return delegate.chat(request);
        }

        @Override
        public <T> LlmStructuredResponse<T> chatForObject(LlmRequest request, Class<T> responseType) {
            LlmStructuredResponse<T> response = delegate.chatForObject(request, responseType);
            structuredResponses.add(response);
            return response;
        }

        boolean isJsonParseSuccess() {
            return !structuredResponses.isEmpty() && structuredResponses.stream().allMatch(LlmStructuredResponse::isSuccess);
        }

        boolean isLlmSuccess() {
            return !structuredResponses.isEmpty() && structuredResponses.stream()
                    .allMatch(response -> response.isSuccess() || response.getStatus() == LlmCallStatus.DISABLED);
        }

        String errorMessage() {
            return structuredResponses.stream()
                    .map(LlmStructuredResponse::getErrorMessage)
                    .filter(StrUtil::isNotBlank)
                    .distinct()
                    .collect(Collectors.joining(" | "));
        }

        Integer promptTokens() {
            return sumTokens(LlmStructuredResponse::getPromptTokens);
        }

        Integer completionTokens() {
            return sumTokens(LlmStructuredResponse::getCompletionTokens);
        }

        Integer totalTokens() {
            return sumTokens(LlmStructuredResponse::getTotalTokens);
        }

        private Integer sumTokens(java.util.function.Function<LlmStructuredResponse<?>, Integer> extractor) {
            int total = structuredResponses.stream()
                    .map(extractor)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum();
            return total == 0 ? null : total;
        }
    }
}
