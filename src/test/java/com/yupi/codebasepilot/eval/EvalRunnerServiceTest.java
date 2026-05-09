package com.yupi.codebasepilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.codebasepilot.eval.dto.EvalReportDto;
import com.yupi.codebasepilot.llm.LlmService;
import com.yupi.codebasepilot.llm.dto.LlmCallStatus;
import com.yupi.codebasepilot.llm.dto.LlmRequest;
import com.yupi.codebasepilot.llm.dto.LlmResponse;
import com.yupi.codebasepilot.llm.dto.LlmStructuredResponse;
import com.yupi.codebasepilot.repo.entity.CodeChunk;
import com.yupi.codebasepilot.repo.entity.Repo;
import com.yupi.codebasepilot.repo.enums.CodeChunkType;
import com.yupi.codebasepilot.repo.enums.RepoIndexedStatus;
import com.yupi.codebasepilot.repo.repository.CodeChunkRepository;
import com.yupi.codebasepilot.repo.repository.RepoRepository;
import com.yupi.codebasepilot.repo.service.CodeSearchService;
import com.yupi.codebasepilot.task.dto.DiagnosisHypothesisDto;
import com.yupi.codebasepilot.task.dto.DiagnosisOutputDto;
import com.yupi.codebasepilot.task.dto.PatchPlanOutputDto;
import com.yupi.codebasepilot.task.dto.ReviewerDecisionDto;
import com.yupi.codebasepilot.task.repository.AgentTaskRepository;
import com.yupi.codebasepilot.task.service.AgentArtifactService;
import com.yupi.codebasepilot.task.service.AgentTaskWorkspaceService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@SpringBootTest
@Transactional
class EvalRunnerServiceTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CodeSearchService codeSearchService;

    @Autowired
    private RepoRepository repoRepository;

    @Autowired
    private CodeChunkRepository codeChunkRepository;

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Autowired
    private AgentTaskWorkspaceService agentTaskWorkspaceService;

    @Autowired
    private AgentArtifactService agentArtifactService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        codeChunkRepository.deleteByRepoId("repo_eval_test");
        repoRepository.findById("repo_eval_test").ifPresent(repoRepository::delete);

        Repo repo = new Repo();
        repo.setId("repo_eval_test");
        repo.setName("eval-demo");
        repo.setUrl("https://github.com/example/eval-demo.git");
        repo.setBranch("main");
        repo.setLocalPath("D:/tmp/eval-demo");
        repo.setIndexedStatus(RepoIndexedStatus.INDEXED);
        repo.setFileCount(2);
        repo.setChunkCount(2);
        repoRepository.save(repo);

        codeChunkRepository.save(buildChunk(
                "chunk_eval_1",
                "src/main/java/com/example/AuthFilter.java",
                "public class AuthFilter { void validateToken(String token) { if (token == null) { throw new RuntimeException(\"login token exception\"); } } }"
        ));
        codeChunkRepository.save(buildChunk(
                "chunk_eval_2",
                "src/main/java/com/example/LoginController.java",
                "public class LoginController { void login() { authFilter.validateToken(token); } }"
        ));
    }

    @Test
    void shouldGenerateMultiStrategyEvalReportJson() throws IOException {
        Path casesFile = tempDir.resolve("eval_cases.json");
        Files.writeString(casesFile, """
                [
                  {
                    "id": "case_001",
                    "repoId": "repo_eval_test",
                    "question": "login token exception",
                    "expectedFiles": ["AuthFilter.java", "LoginController.java"],
                    "expectedKeywords": ["token", "login", "exception"],
                    "expectedRootCause": "token 为空未正确处�?,
                    "caseType": "BUG_DIAGNOSIS",
                    "difficulty": "medium"
                  }
                ]
                """);
        Path reportFile = tempDir.resolve("eval_report.json");
        EvalRunnerService evalRunnerService = new EvalRunnerService(
                objectMapper,
                codeSearchService,
                new DefaultResourceLoader(),
                new StubLlmService(),
                agentTaskRepository,
                agentTaskWorkspaceService,
                agentArtifactService,
                "file:" + casesFile,
                reportFile.toString()
        );

        EvalReportDto report = evalRunnerService.runDefaultEval();

        Assertions.assertEquals(1, report.getTotalCases());
        Assertions.assertEquals(7, report.getTotalRuns());
        Assertions.assertTrue(report.getRecallAt5() > 0D);
        Assertions.assertTrue(report.getEvidenceGroundingRate() > 0D);
        Assertions.assertTrue(report.getJsonParseSuccessRate() > 0D);
        Assertions.assertEquals(reportFile.toString().replace("\\", "/"), report.getReportPath());
        Assertions.assertTrue(Files.exists(reportFile));
        Assertions.assertEquals(7, report.getPerCaseResults().size());
        Assertions.assertEquals(7, report.getStrategyReports().size());

        var reviewerResult = report.getPerCaseResults().stream()
                .filter(result -> "AGENT_RAG_REVIEWER".equals(result.getStrategy()))
                .findFirst()
                .orElseThrow();
        Assertions.assertTrue(reviewerResult.isDiagnosisHasEvidence());
        Assertions.assertTrue(Boolean.TRUE.equals(reviewerResult.getReviewerPassed()));
        Assertions.assertEquals("DELIVER", reviewerResult.getReviewerAction());
        Assertions.assertTrue(reviewerResult.isRootCauseKeywordHit());
        Assertions.assertNotNull(reviewerResult.getTotalTokens());

        var patchResult = report.getPerCaseResults().stream()
                .filter(result -> "AGENT_RAG_REVIEWER_PATCH".equals(result.getStrategy()))
                .findFirst()
                .orElseThrow();
        Assertions.assertTrue(patchResult.isPatchGenerated());
        Assertions.assertTrue(patchResult.getFilesToChange().contains("src/main/java/com/example/AuthFilter.java"));
    }

    private CodeChunk buildChunk(String id, String filePath, String content) {
        CodeChunk codeChunk = new CodeChunk();
        codeChunk.setId(id);
        codeChunk.setRepoId("repo_eval_test");
        codeChunk.setFilePath(filePath);
        codeChunk.setLanguage("java");
        codeChunk.setChunkType(CodeChunkType.FUNCTION);
        codeChunk.setStartLine(1);
        codeChunk.setEndLine(20);
        codeChunk.setContent(content);
        codeChunk.setContentHash(id + "_hash");
        codeChunk.setTokenCount(content.split("\\s+").length);
        return codeChunk;
    }

    private static final class StubLlmService implements LlmService {

        @Override
        public LlmResponse chat(LlmRequest request) {
            return LlmResponse.builder()
                    .success(true)
                    .status(LlmCallStatus.SUCCESS)
                    .content("stub")
                    .model("stub-model")
                    .latencyMs(5L)
                    .promptTokens(10)
                    .completionTokens(20)
                    .totalTokens(30)
                    .build();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> LlmStructuredResponse<T> chatForObject(LlmRequest request, Class<T> responseType) {
            Object data;
            if ("REVIEW".equals(request.getScene())) {
                data = ReviewerDecisionDto.builder()
                        .passed(true)
                        .reason("The diagnosis is grounded and safe to deliver.")
                        .unsupportedClaims(List.of())
                        .risk("Low")
                        .suggestedAction("DELIVER")
                        .build();
            } else if ("PATCH_PLAN".equals(request.getScene())) {
                data = PatchPlanOutputDto.builder()
                        .filesToChange(List.of("src/main/java/com/example/AuthFilter.java"))
                        .patchPlan("Add a null check before token validation and return a controlled 400 response.")
                        .diffPreview("--- a/AuthFilter.java\n+++ b/AuthFilter.java\n@@\n+if (token == null) { throw new IllegalArgumentException(\"token is required\"); }")
                        .testSuggestions(List.of("Add a test for null token input."))
                        .risks(List.of("Changing validation flow may affect existing callers."))
                        .needMoreInfo(false)
                        .missingInfo(List.of())
                        .build();
            } else {
                data = DiagnosisOutputDto.builder()
                        .summary("The login 500 is likely related to token validation in AuthFilter.")
                        .hypotheses(List.of(
                                DiagnosisHypothesisDto.builder()
                                        .cause("token 为空未正确处�?)
                                        .evidence(List.of("src/main/java/com/example/AuthFilter.java:1-20"))
                                        .confidence(0.92)
                                        .risk("Low")
                                        .build()
                        ))
                        .needMoreSearch(false)
                        .missingInfo(List.of())
                        .build();
            }
            return LlmStructuredResponse.<T>builder()
                    .success(true)
                    .status(LlmCallStatus.SUCCESS)
                    .data((T) data)
                    .content(objectToJson(data))
                    .model("stub-model")
                    .latencyMs(15L)
                    .promptTokens(50)
                    .completionTokens(80)
                    .totalTokens(130)
                    .rawResponse(Map.of("scene", request.getScene()))
                    .build();
        }

        private String objectToJson(Object value) {
            try {
                return new ObjectMapper().writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
