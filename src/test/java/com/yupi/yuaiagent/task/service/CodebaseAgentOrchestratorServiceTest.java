package com.yupi.yuaiagent.task.service;

import com.yupi.yuaiagent.llm.LlmService;
import com.yupi.yuaiagent.llm.dto.LlmCallStatus;
import com.yupi.yuaiagent.llm.dto.LlmRequest;
import com.yupi.yuaiagent.llm.dto.LlmStructuredResponse;
import com.yupi.yuaiagent.repo.entity.CodeChunk;
import com.yupi.yuaiagent.repo.entity.Repo;
import com.yupi.yuaiagent.repo.enums.CodeChunkType;
import com.yupi.yuaiagent.repo.enums.RepoIndexedStatus;
import com.yupi.yuaiagent.repo.repository.CodeChunkRepository;
import com.yupi.yuaiagent.repo.repository.RepoRepository;
import com.yupi.yuaiagent.task.agent.AgentContext;
import com.yupi.yuaiagent.task.agent.AgentStepSummary;
import com.yupi.yuaiagent.task.agent.NextAction;
import com.yupi.yuaiagent.task.agent.impl.ReviewerAgent;
import com.yupi.yuaiagent.task.dto.AgentTaskCreateRequest;
import com.yupi.yuaiagent.task.dto.AgentTaskResponse;
import com.yupi.yuaiagent.task.dto.CodeFlowStepDto;
import com.yupi.yuaiagent.task.dto.CodeModuleDto;
import com.yupi.yuaiagent.task.dto.DiagnosisHypothesisDto;
import com.yupi.yuaiagent.task.dto.DiagnosisOutputDto;
import com.yupi.yuaiagent.task.dto.ModuleSummaryOutputDto;
import com.yupi.yuaiagent.task.dto.PatchPlanOutputDto;
import com.yupi.yuaiagent.task.dto.ReviewerDecisionDto;
import com.yupi.yuaiagent.task.repository.AgentApprovalRepository;
import com.yupi.yuaiagent.task.repository.AgentArtifactRepository;
import com.yupi.yuaiagent.task.repository.AgentTaskEventRepository;
import com.yupi.yuaiagent.task.repository.AgentTaskRepository;
import com.yupi.yuaiagent.task.repository.AgentTaskStepRepository;
import com.yupi.yuaiagent.task.repository.AgentToolCallRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@SpringBootTest
@Transactional
class CodebaseAgentOrchestratorServiceTest {

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private CodebaseAgentOrchestratorService codebaseAgentOrchestratorService;

    @Autowired
    private ReviewerAgent reviewerAgent;

    @Autowired
    private RepoRepository repoRepository;

    @Autowired
    private CodeChunkRepository codeChunkRepository;

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Autowired
    private AgentTaskStepRepository agentTaskStepRepository;

    @Autowired
    private AgentArtifactRepository agentArtifactRepository;

    @Autowired
    private AgentTaskEventRepository agentTaskEventRepository;

    @Autowired
    private AgentApprovalRepository agentApprovalRepository;

    @Autowired
    private AgentToolCallRepository agentToolCallRepository;

    @MockBean
    private LlmService llmService;

    @BeforeEach
    void setUp() {
        agentToolCallRepository.deleteAll();
        agentApprovalRepository.deleteAll();
        agentArtifactRepository.deleteAll();
        agentTaskStepRepository.deleteAll();
        agentTaskEventRepository.deleteAll();
        agentTaskRepository.deleteAll();
        codeChunkRepository.deleteAll();
        repoRepository.deleteAll();

        Repo repo = new Repo();
        repo.setId("repo_bug_test");
        repo.setName("bug-repo");
        repo.setUrl("https://github.com/example/bug-repo.git");
        repo.setBranch("main");
        repo.setLocalPath("D:/tmp/repos/repo_bug_test");
        repo.setIndexedStatus(RepoIndexedStatus.INDEXED);
        repo.setFileCount(1);
        repo.setChunkCount(1);
        repoRepository.save(repo);

        CodeChunk chunk = new CodeChunk();
        chunk.setId("chunk_bug_test");
        chunk.setRepoId("repo_bug_test");
        chunk.setFilePath("src/main/java/com/example/LoginController.java");
        chunk.setLanguage("java");
        chunk.setSymbolName("login");
        chunk.setChunkType(CodeChunkType.FUNCTION);
        chunk.setStartLine(35);
        chunk.setEndLine(78);
        chunk.setContent("""
                public Result login(String token) {
                    if (token == null) {
                        throw new IllegalStateException("token validation exception");
                    }
                    return authService.login(token);
                }
                """);
        chunk.setContentHash("hash_bug_test");
        chunk.setTokenCount(20);
        chunk.setCreatedAt(LocalDateTime.now());
        codeChunkRepository.save(chunk);

        CodeChunk securityChunk = new CodeChunk();
        securityChunk.setId("chunk_security_config");
        securityChunk.setRepoId("repo_bug_test");
        securityChunk.setFilePath("src/main/java/com/example/config/SecurityConfig.java");
        securityChunk.setLanguage("java");
        securityChunk.setSymbolName("SecurityConfig");
        securityChunk.setChunkType(CodeChunkType.CLASS);
        securityChunk.setStartLine(1);
        securityChunk.setEndLine(60);
        securityChunk.setContent("""
                @Configuration
                public class SecurityConfig {
                    public SecurityFilterChain securityFilterChain(HttpSecurity http) { return http.build(); }
                }
                """);
        securityChunk.setContentHash("hash_security_config");
        securityChunk.setTokenCount(18);
        securityChunk.setCreatedAt(LocalDateTime.now());
        codeChunkRepository.save(securityChunk);

        CodeChunk serviceChunk = new CodeChunk();
        serviceChunk.setId("chunk_auth_service");
        serviceChunk.setRepoId("repo_bug_test");
        serviceChunk.setFilePath("src/main/java/com/example/service/AuthService.java");
        serviceChunk.setLanguage("java");
        serviceChunk.setSymbolName("AuthService");
        serviceChunk.setChunkType(CodeChunkType.CLASS);
        serviceChunk.setStartLine(1);
        serviceChunk.setEndLine(48);
        serviceChunk.setContent("""
                @Service
                public class AuthService {
                    public Result login(String token) { return Result.ok(token); }
                }
                """);
        serviceChunk.setContentHash("hash_auth_service");
        serviceChunk.setTokenCount(16);
        serviceChunk.setCreatedAt(LocalDateTime.now());
        codeChunkRepository.save(serviceChunk);

        CodeChunk readmeChunk = new CodeChunk();
        readmeChunk.setId("chunk_readme");
        readmeChunk.setRepoId("repo_bug_test");
        readmeChunk.setFilePath("README.md");
        readmeChunk.setLanguage("markdown");
        readmeChunk.setSymbolName("README");
        readmeChunk.setChunkType(CodeChunkType.DOC);
        readmeChunk.setStartLine(1);
        readmeChunk.setEndLine(18);
        readmeChunk.setContent("""
                # Sample Project
                This project includes login, security config, and basic auth service modules.
                """);
        readmeChunk.setContentHash("hash_readme");
        readmeChunk.setTokenCount(12);
        readmeChunk.setCreatedAt(LocalDateTime.now());
        codeChunkRepository.save(readmeChunk);

        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(DiagnosisOutputDto.class)))
                .thenReturn(LlmStructuredResponse.<DiagnosisOutputDto>builder()
                        .success(true)
                        .status(LlmCallStatus.SUCCESS)
                        .data(DiagnosisOutputDto.builder()
                                .summary("Token validation branch may throw an uncaught exception.")
                                .hypotheses(List.of(DiagnosisHypothesisDto.builder()
                                        .cause("token validation branch misses a safe fallback")
                                        .evidence(List.of("src/main/java/com/example/LoginController.java:35-78"))
                                        .confidence(0.82D)
                                        .risk("needs runtime confirmation")
                                        .build()))
                                .needMoreSearch(false)
                                .missingInfo(List.of())
                                .build())
                        .content("{\"summary\":\"Token validation branch may throw an uncaught exception.\"}")
                        .latencyMs(10L)
                        .build());
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(ReviewerDecisionDto.class)))
                .thenReturn(LlmStructuredResponse.<ReviewerDecisionDto>builder()
                        .success(true)
                        .status(LlmCallStatus.SUCCESS)
                        .data(ReviewerDecisionDto.builder()
                                .passed(true)
                                .reason("Reviewer accepted the diagnosis because the evidence supports the cause and the answer addresses the user goal.")
                                .unsupportedClaims(List.of())
                                .risk("Please verify the suspected branch with runtime logs before applying a fix.")
                                .suggestedAction("DELIVER")
                                .build())
                        .content("{\"passed\":true}")
                        .latencyMs(10L)
                        .build());
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(ModuleSummaryOutputDto.class)))
                .thenAnswer(invocation -> {
                    LlmRequest llmRequest = invocation.getArgument(0);
                    String prompt = llmRequest.getUserPrompt();
                    if (prompt.contains("FLOW_ANALYSIS") && (prompt.contains("menu") || prompt.contains("permission") || prompt.contains("role"))) {
                        return LlmStructuredResponse.<ModuleSummaryOutputDto>builder()
                                .success(true)
                                .status(LlmCallStatus.SUCCESS)
                                .data(ModuleSummaryOutputDto.builder()
                                        .intent("FLOW_ANALYSIS")
                                        .targetModule("menu role")
                                        .outputSchema("FLOW_SUMMARY")
                                        .subType("FLOW_SUMMARY")
                                        .summary("Menus and permissions are loaded through role-aware services after authentication.")
                                        .flowSteps(List.of(
                                                CodeFlowStepDto.builder()
                                                        .step("Role lookup")
                                                        .description("The role layer resolves the current user's role bindings before loading menu data.")
                                                        .keyFiles(List.of("RoleService.java", "RoleMapper.java"))
                                                        .evidence(List.of("src/main/java/com/example/config/SecurityConfig.java:1-60"))
                                                        .build(),
                                                CodeFlowStepDto.builder()
                                                        .step("Menu and permission load")
                                                        .description("Menu services assemble the accessible menu tree and permission set for the signed-in user.")
                                                        .keyFiles(List.of("MenuService.java", "MenuMapper.java"))
                                                        .evidence(List.of("README.md:1-18"))
                                                        .build()
                                        ))
                                        .riskNotes(List.of("Repository evidence for menu-specific mappers is limited in this fixture repo."))
                                        .needMoreSearch(false)
                                        .missingInfo(List.of())
                                        .build())
                                .content("{\"intent\":\"FLOW_ANALYSIS\"}")
                                .latencyMs(10L)
                                .build();
                    }
                    if (prompt.contains("API_CALL_CHAIN")) {
                        return LlmStructuredResponse.<ModuleSummaryOutputDto>builder()
                                .success(true)
                                .status(LlmCallStatus.SUCCESS)
                                .data(ModuleSummaryOutputDto.builder()
                                        .intent("API_CALL_CHAIN")
                                        .targetModule("login")
                                        .outputSchema("CALL_CHAIN")
                                        .subType("CALL_CHAIN")
                                        .summary("The login query path flows from LoginController into AuthService and then into repository-like persistence adapters.")
                                        .callChain(List.of(
                                                Map.of(
                                                        "layer", "Controller",
                                                        "responsibility", "Accepts login requests and validates the token branch.",
                                                        "methods", List.of("login"),
                                                        "keyFiles", List.of("LoginController.java"),
                                                        "evidence", List.of("src/main/java/com/example/LoginController.java:35-78")
                                                ),
                                                Map.of(
                                                        "layer", "Service",
                                                        "responsibility", "Handles the main authentication orchestration.",
                                                        "methods", List.of("login"),
                                                        "keyFiles", List.of("AuthService.java"),
                                                        "evidence", List.of("src/main/java/com/example/service/AuthService.java:1-48")
                                                ),
                                                Map.of(
                                                        "layer", "Mapper",
                                                        "responsibility", "The fixture repo does not expose a mapper, so persistence remains inferred from service boundaries.",
                                                        "methods", List.of(),
                                                        "keyFiles", List.of(),
                                                        "evidence", List.of("README.md:1-18")
                                                )
                                        ))
                                        .notesAndRisks(List.of("This fixture repo has no dedicated mapper class, so the final hop is partial."))
                                        .needMoreSearch(false)
                                        .missingInfo(List.of())
                                        .build())
                                .content("{\"intent\":\"API_CALL_CHAIN\"}")
                                .latencyMs(10L)
                                .build();
                    }
                    if (prompt.contains("FLOW_ANALYSIS")) {
                        return LlmStructuredResponse.<ModuleSummaryOutputDto>builder()
                                .success(true)
                                .status(LlmCallStatus.SUCCESS)
                                .data(ModuleSummaryOutputDto.builder()
                                        .intent("FLOW_ANALYSIS")
                                        .targetModule("login auth")
                                        .outputSchema("FLOW_SUMMARY")
                                        .subType("FLOW_SUMMARY")
                                        .summary("The login flow enters through LoginController, delegates to AuthService, and is constrained by SecurityConfig.")
                                        .flowSteps(List.of(
                                                CodeFlowStepDto.builder()
                                                        .step("Login request entry")
                                                        .description("LoginController receives the token-bearing request and routes it into the auth flow.")
                                                        .keyFiles(List.of("LoginController.java"))
                                                        .evidence(List.of("src/main/java/com/example/LoginController.java:35-78"))
                                                        .build(),
                                                CodeFlowStepDto.builder()
                                                        .step("Authentication service handling")
                                                        .description("AuthService performs the service-layer login handling for authenticated requests.")
                                                        .keyFiles(List.of("AuthService.java"))
                                                        .evidence(List.of("src/main/java/com/example/service/AuthService.java:1-48"))
                                                        .build(),
                                                CodeFlowStepDto.builder()
                                                        .step("Security configuration and filtering")
                                                        .description("SecurityConfig defines the filter-chain behavior that wraps the login flow.")
                                                        .keyFiles(List.of("SecurityConfig.java"))
                                                        .evidence(List.of("src/main/java/com/example/config/SecurityConfig.java:1-60"))
                                                        .build()
                                        ))
                                        .riskNotes(List.of("Runtime filters outside the visible snippets may add extra auth handling."))
                                        .needMoreSearch(false)
                                        .missingInfo(List.of())
                                        .build())
                                .content("{\"intent\":\"FLOW_ANALYSIS\"}")
                                .latencyMs(10L)
                                .build();
                    }
                    if (prompt.contains("MODULE_DETAIL")) {
                        return LlmStructuredResponse.<ModuleSummaryOutputDto>builder()
                                .success(true)
                                .status(LlmCallStatus.SUCCESS)
                                .data(ModuleSummaryOutputDto.builder()
                                        .intent("MODULE_DETAIL")
                                        .targetModule("login")
                                        .outputSchema("MODULE_DETAIL")
                                        .subType("MODULE_DETAIL")
                                        .summary("The login module is centered on a controller entry point and a coordinating service layer.")
                                        .operations(List.of(
                                                Map.of(
                                                        "operation", "query",
                                                        "controller", "LoginController#login",
                                                        "service", "AuthService#login",
                                                        "mapper", "",
                                                        "evidence", List.of(
                                                                "src/main/java/com/example/LoginController.java:35-78",
                                                                "src/main/java/com/example/service/AuthService.java:1-48"
                                                        )
                                                ),
                                                Map.of(
                                                        "operation", "validate token",
                                                        "controller", "LoginController#login",
                                                        "service", "AuthService#login",
                                                        "mapper", "",
                                                        "evidence", List.of("src/main/java/com/example/LoginController.java:35-78")
                                                )
                                        ))
                                        .notesAndRisks(List.of("The fixture repo only exposes a small login slice, so CRUD operations beyond login are absent."))
                                        .missingInfo(List.of())
                                        .needMoreSearch(false)
                                        .build())
                                .content("{\"intent\":\"MODULE_DETAIL\"}")
                                .latencyMs(10L)
                                .build();
                    }
                    return LlmStructuredResponse.<ModuleSummaryOutputDto>builder()
                            .success(true)
                            .status(LlmCallStatus.SUCCESS)
                            .data(ModuleSummaryOutputDto.builder()
                                    .intent("OVERALL_STRUCTURE")
                                    .subType("MODULE_SUMMARY")
                                    .summary("The project is centered on authentication and layered backend modules.")
                                    .modules(List.of(
                                            CodeModuleDto.builder()
                                                    .name("Authentication and security")
                                                    .responsibility("Handles login entry points, security configuration, and token-aware authentication flow.")
                                                    .evidence(List.of(
                                                            "src/main/java/com/example/LoginController.java:35-78",
                                                            "src/main/java/com/example/config/SecurityConfig.java:1-60"
                                                    ))
                                                    .keyFiles(List.of("LoginController.java", "SecurityConfig.java"))
                                                    .build(),
                                            CodeModuleDto.builder()
                                                    .name("Application services")
                                                    .responsibility("Provides service-layer login handling and shared business orchestration.")
                                                    .evidence(List.of("src/main/java/com/example/service/AuthService.java:1-48"))
                                                    .keyFiles(List.of("AuthService.java"))
                                                    .build()
                                    ))
                                    .architectureNotes(List.of(
                                            "The backend follows a Controller / Service / Config layering style.",
                                            "README and security configuration confirm the project is oriented around login/auth flows."
                                    ))
                                    .riskNotes(List.of())
                                    .needMoreSearch(false)
                                    .missingInfo(List.of())
                                    .build())
                            .content("{\"intent\":\"OVERALL_STRUCTURE\"}")
                            .latencyMs(10L)
                            .build();
                });
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(PatchPlanOutputDto.class)))
                .thenReturn(LlmStructuredResponse.<PatchPlanOutputDto>builder()
                        .success(true)
                        .status(LlmCallStatus.SUCCESS)
                        .data(PatchPlanOutputDto.builder()
                                .filesToChange(List.of("src/main/java/com/example/LoginController.java"))
                                .patchPlan("Add a null-token guard and return a stable error response before calling authService.")
                                .diffPreview("""
                                        --- a/src/main/java/com/example/LoginController.java
                                        +++ b/src/main/java/com/example/LoginController.java
                                        @@
                                        - if (token == null) { throw new IllegalStateException("token validation exception"); }
                                        + if (token == null) { return Result.failure("token is required"); }
                                        """.trim())
                                .testSuggestions(List.of("Add a regression test for null token requests."))
                                .risks(List.of("Changing the error contract may affect existing callers."))
                                .needMoreInfo(false)
                                .missingInfo(List.of())
                                .build())
                        .content("{\"filesToChange\":[\"src/main/java/com/example/LoginController.java\"]}")
                        .latencyMs(10L)
                        .build());
    }

    @Test
    void shouldRunBugDiagnosisWorkflowAndGenerateArtifacts() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("BUG_DIAGNOSIS");
        request.setGoal("login token exception controller");

        AgentTaskResponse created = agentTaskService.createTask(request);

        codebaseAgentOrchestratorService.runTask(created.getTaskId());

        AgentTaskResponse task = agentTaskService.getTask(created.getTaskId());

        Assertions.assertEquals("SUCCEEDED", task.getStatus());
        Assertions.assertTrue(task.getSteps().size() >= 5);
        Assertions.assertTrue(task.getSteps().stream()
                .anyMatch(step -> "PlannerAgent".equals(step.getAssignedAgent())));
        Assertions.assertTrue(task.getSteps().stream()
                .anyMatch(step -> "CodeSearchAgent".equals(step.getAssignedAgent()) && !step.getEvidenceRefs().isEmpty()));
        Assertions.assertTrue(task.getArtifacts().stream()
                .anyMatch(artifact -> "ROOT_CAUSE_REPORT".equals(artifact.getArtifactType())));
        Assertions.assertTrue(task.getArtifacts().stream()
                .anyMatch(artifact -> "CODE_EVIDENCE".equals(artifact.getArtifactType()) && !artifact.getEvidenceRefs().isEmpty()));
        Assertions.assertNotNull(task.getFinalResult());
        Assertions.assertNotNull(task.getReviewSummary());
        Assertions.assertEquals("NONE", task.getReviewSuggestedAction());
        Mockito.verify(llmService, Mockito.atLeastOnce())
                .chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(ReviewerDecisionDto.class));
    }

    @Test
    void shouldRunPatchSuggestionWorkflowAndGeneratePatchPlanArtifact() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("PATCH_SUGGESTION");
        request.setGoal("Generate a safe patch plan for the login token validation exception.");

        AgentTaskResponse created = agentTaskService.createTask(request);

        codebaseAgentOrchestratorService.runTask(created.getTaskId());

        AgentTaskResponse task = agentTaskService.getTask(created.getTaskId());

        Assertions.assertEquals("SUCCEEDED", task.getStatus());
        Assertions.assertTrue(task.getSteps().stream()
                .anyMatch(step -> "PatchAgent".equals(step.getAssignedAgent()) && "SUCCEEDED".equals(step.getStatus())));
        Assertions.assertTrue(task.getArtifacts().stream()
                .anyMatch(artifact -> "PATCH_PLAN".equals(artifact.getArtifactType())
                        && artifact.getStructuredContent() != null
                        && artifact.getEvidenceRefs() != null
                        && !artifact.getEvidenceRefs().isEmpty()));
    }

    @Test
    void shouldRunCodeUnderstandingWorkflowAndGenerateModuleSummaryArtifact() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("CODE_UNDERSTANDING");
        request.setGoal("请分析这个项目的整体代码结构，说明后端主要模块有哪些，每个模块大概负责什么，并给出相关代码文件作为证据。");

        AgentTaskResponse created = agentTaskService.createTask(request);

        codebaseAgentOrchestratorService.runTask(created.getTaskId());

        AgentTaskResponse task = agentTaskService.getTask(created.getTaskId());

        Assertions.assertEquals("SUCCEEDED", task.getStatus());
        Assertions.assertTrue(task.getSteps().stream().anyMatch(step -> "CodeSearchAgent".equals(step.getAssignedAgent())));
        Assertions.assertTrue(task.getSteps().stream().anyMatch(step -> "CodeUnderstandingAgent".equals(step.getAssignedAgent())));
        Assertions.assertTrue(task.getSteps().stream().noneMatch(step -> "LegacyWorker".equals(step.getAssignedAgent())));
        Assertions.assertTrue(task.getArtifacts().stream()
                .anyMatch(artifact -> "MODULE_SUMMARY".equals(artifact.getArtifactType())
                        && artifact.getStructuredContent() != null
                        && artifact.getStructuredContent().get("modules") != null));
        Assertions.assertTrue(task.getArtifacts().stream()
                .anyMatch(artifact -> "CODE_EVIDENCE".equals(artifact.getArtifactType()) && !artifact.getEvidenceRefs().isEmpty()));
    }

    @Test
    void shouldRunCodeUnderstandingLoginFlowAnalysisWithGroundedEvidence() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("CODE_UNDERSTANDING");
        request.setGoal("请分析这个项目的登录认证流程，从登录入口开始说明 Controller、Service、Security 配置如何协作，并给出代码证据。");

        AgentTaskResponse created = agentTaskService.createTask(request);

        codebaseAgentOrchestratorService.runTask(created.getTaskId());

        AgentTaskResponse task = agentTaskService.getTask(created.getTaskId());

        Assertions.assertEquals("SUCCEEDED", task.getStatus());
        Assertions.assertTrue(task.getSteps().stream()
                .filter(step -> "CodeSearchAgent".equals(step.getAssignedAgent()))
                .anyMatch(step -> step.getEvidenceRefs() != null && !step.getEvidenceRefs().isEmpty()));
        Assertions.assertTrue(task.getArtifacts().stream()
                .filter(artifact -> "MODULE_SUMMARY".equals(artifact.getArtifactType()))
                .anyMatch(artifact -> artifact.getEvidenceRefs() != null
                        && artifact.getEvidenceRefs().stream().anyMatch(ref -> ref.getFilePath().contains("SecurityConfig"))));
    }

    @Test
    void shouldDeliverPartialCodeUnderstandingResultWhenGroundedButIncomplete() {
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(ModuleSummaryOutputDto.class)))
                .thenReturn(LlmStructuredResponse.<ModuleSummaryOutputDto>builder()
                        .success(true)
                        .status(LlmCallStatus.SUCCESS)
                        .data(ModuleSummaryOutputDto.builder()
                                .intent("FLOW_ANALYSIS")
                                .targetModule("login auth")
                                .outputSchema("FLOW_SUMMARY")
                                .subType("FLOW_SUMMARY")
                                .summary("The login/auth flow can be partially confirmed from the available grounded evidence.")
                                .flowSteps(List.of(
                                        CodeFlowStepDto.builder()
                                                .step("Login request entry")
                                                .description("LoginController receives the request and routes it into the auth flow.")
                                                .keyFiles(List.of("LoginController.java"))
                                                .evidence(List.of("src/main/java/com/example/LoginController.java:35-78"))
                                                .build(),
                                        CodeFlowStepDto.builder()
                                                .step("Security configuration")
                                                .description("SecurityConfig defines the surrounding filter-chain behavior.")
                                                .keyFiles(List.of("SecurityConfig.java"))
                                                .evidence(List.of("src/main/java/com/example/config/SecurityConfig.java:1-60"))
                                                .build()
                                ))
                                .riskNotes(List.of("The JWT success/failure handler implementation is still missing from the visible evidence."))
                                .needMoreSearch(true)
                                .missingInfo(List.of("AuthenticationSuccessHandler implementation", "JWT token utility or filter"))
                                .build())
                        .content("{\"intent\":\"FLOW_ANALYSIS\"}")
                        .latencyMs(10L)
                        .build());

        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("CODE_UNDERSTANDING");
        request.setGoal("Please analyze the login and authentication flow, explain the current grounded flow, and cite code evidence.");

        AgentTaskResponse created = agentTaskService.createTask(request);
        codebaseAgentOrchestratorService.runTask(created.getTaskId());
        AgentTaskResponse task = agentTaskService.getTask(created.getTaskId());

        Assertions.assertEquals("SUCCEEDED", task.getStatus());
        Assertions.assertTrue(task.getArtifacts().stream()
                .filter(artifact -> "MODULE_SUMMARY".equals(artifact.getArtifactType()))
                .anyMatch(artifact -> "true".equals(String.valueOf(artifact.getStructuredContent().get("partial")).replace("\"", ""))
                        && "PARTIAL".equals(String.valueOf(artifact.getStructuredContent().get("deliveryMode")).replace("\"", ""))
                        && artifact.getStructuredContent().get("missingInfo") != null));
    }

    @Test
    void shouldRecoverCodeUnderstandingFromRawJsonWrappedInPlainText() {
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(ModuleSummaryOutputDto.class)))
                .thenReturn(LlmStructuredResponse.<ModuleSummaryOutputDto>builder()
                        .success(false)
                        .status(LlmCallStatus.FAILED)
                        .errorMessage("Could not parse model response as ModuleSummaryOutputDto")
                        .content("""
                                Here is the grounded result:
                                {
                                  "intent": "OVERALL_STRUCTURE",
                                  "subType": "MODULE_SUMMARY",
                                  "outputSchema": "MODULE_SUMMARY",
                                  "summary": "The project is organized around layered backend modules for authentication, configuration, and service orchestration.",
                                  "modules": [
                                    {
                                      "name": "Authentication and security",
                                      "responsibility": "Handles login flow entry, token validation, and security configuration.",
                                      "keyFiles": ["LoginController.java", "SecurityConfig.java"],
                                      "evidence": ["src/main/java/com/example/LoginController.java:35-78", "src/main/java/com/example/config/SecurityConfig.java:1-60"]
                                    }
                                  ],
                                  "architectureNotes": ["The fixture repo follows a Controller / Service / Config split."],
                                  "riskNotes": [],
                                  "needMoreSearch": false,
                                  "missingInfo": []
                                }
                                """)
                        .build());

        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("CODE_UNDERSTANDING");
        request.setGoal("请分析这个项目的整体代码结构，说明后端主要模块有哪些，并给出相关代码证据。");

        AgentTaskResponse created = agentTaskService.createTask(request);
        codebaseAgentOrchestratorService.runTask(created.getTaskId());
        AgentTaskResponse task = agentTaskService.getTask(created.getTaskId());

        Assertions.assertEquals("SUCCEEDED", task.getStatus());
        Assertions.assertTrue(task.getArtifacts().stream()
                .filter(artifact -> "MODULE_SUMMARY".equals(artifact.getArtifactType()))
                .anyMatch(artifact -> String.valueOf(artifact.getStructuredContent()).contains("Authentication and security")));
    }

    @Test
    void shouldDifferentiateOverallStructureAndLoginAuthFlowResults() {
        AgentTaskCreateRequest overallRequest = new AgentTaskCreateRequest();
        overallRequest.setRepoId("repo_bug_test");
        overallRequest.setBusinessType("CODE_UNDERSTANDING");
        overallRequest.setGoal("Please analyze the overall project structure, explain the main backend modules, and cite related code files.");
        AgentTaskResponse overallCreated = agentTaskService.createTask(overallRequest);
        codebaseAgentOrchestratorService.runTask(overallCreated.getTaskId());
        AgentTaskResponse overallTask = agentTaskService.getTask(overallCreated.getTaskId());

        AgentTaskCreateRequest loginRequest = new AgentTaskCreateRequest();
        loginRequest.setRepoId("repo_bug_test");
        loginRequest.setBusinessType("CODE_UNDERSTANDING");
        loginRequest.setGoal("Please analyze the login and authentication flow, explain how the Controller, Service, and Security configuration collaborate, and cite code evidence.");
        AgentTaskResponse loginCreated = agentTaskService.createTask(loginRequest);
        codebaseAgentOrchestratorService.runTask(loginCreated.getTaskId());
        AgentTaskResponse loginTask = agentTaskService.getTask(loginCreated.getTaskId());

        Assertions.assertTrue(overallTask.getArtifacts().stream()
                .filter(artifact -> "MODULE_SUMMARY".equals(artifact.getArtifactType()))
                .anyMatch(artifact -> String.valueOf(artifact.getStructuredContent()).contains("OVERALL_STRUCTURE")
                        && artifact.getStructuredContent().get("modules") != null));
        Assertions.assertTrue(loginTask.getArtifacts().stream()
                .filter(artifact -> "MODULE_SUMMARY".equals(artifact.getArtifactType()))
                .anyMatch(artifact -> String.valueOf(artifact.getStructuredContent()).contains("FLOW_ANALYSIS")
                        && artifact.getStructuredContent().get("flowSteps") != null));
        Assertions.assertTrue(loginTask.getSteps().stream()
                .filter(step -> "CodeSearchAgent".equals(step.getAssignedAgent()))
                .anyMatch(step -> step.getExecutorOutput() != null
                        && step.getExecutorOutput().contains("FLOW_ANALYSIS")
                        && step.getExecutorOutput().contains("security")
                        && step.getExecutorOutput().contains("loadUserByUsername")));
    }

    @Test
    void shouldRunPermissionMenuFlowUnderstandingWithDedicatedQueries() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("CODE_UNDERSTANDING");
        request.setGoal("Please analyze how menus and permissions are loaded after login, explain the role, menu, and permission flow, and cite code evidence.");

        AgentTaskResponse created = agentTaskService.createTask(request);
        codebaseAgentOrchestratorService.runTask(created.getTaskId());
        AgentTaskResponse task = agentTaskService.getTask(created.getTaskId());

        Assertions.assertEquals("SUCCEEDED", task.getStatus());
        Assertions.assertTrue(task.getSteps().stream()
                .filter(step -> "CodeSearchAgent".equals(step.getAssignedAgent()))
                .anyMatch(step -> step.getExecutorOutput() != null
                        && step.getExecutorOutput().contains("FLOW_ANALYSIS")
                        && step.getExecutorOutput().contains("menu")
                        && step.getExecutorOutput().contains("role")));
        Assertions.assertTrue(task.getArtifacts().stream()
                .filter(artifact -> "MODULE_SUMMARY".equals(artifact.getArtifactType()))
                .anyMatch(artifact -> String.valueOf(artifact.getStructuredContent()).contains("FLOW_ANALYSIS")
                        && artifact.getStructuredContent().get("flowSteps") != null));
    }

    @Test
    void shouldRunModuleDetailUnderstandingWithOperations() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("CODE_UNDERSTANDING");
        request.setGoal("Please analyze the login module structure, explain which Controller, Service, and Mapper responsibilities support token validation and query-style handling, and cite code evidence.");

        AgentTaskResponse created = agentTaskService.createTask(request);
        codebaseAgentOrchestratorService.runTask(created.getTaskId());
        AgentTaskResponse task = agentTaskService.getTask(created.getTaskId());

        Assertions.assertEquals("SUCCEEDED", task.getStatus());
        Assertions.assertTrue(task.getArtifacts().stream()
                .anyMatch(artifact -> "MODULE_SUMMARY".equals(artifact.getArtifactType())));
    }

    @Test
    void shouldPreferApiCallChainOverModuleDetailWhenGoalAsksForChain() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("CODE_UNDERSTANDING");
        request.setGoal("Please analyze a login endpoint call chain from Controller to Service to Mapper, explain each layer responsibility, and cite code evidence.");

        AgentTaskResponse created = agentTaskService.createTask(request);
        codebaseAgentOrchestratorService.runTask(created.getTaskId());
        AgentTaskResponse task = agentTaskService.getTask(created.getTaskId());

        Assertions.assertEquals("SUCCEEDED", task.getStatus());
        Assertions.assertTrue(task.getSteps().stream()
                .filter(step -> "CodeSearchAgent".equals(step.getAssignedAgent()))
                .anyMatch(step -> step.getExecutorOutput() != null
                        && step.getExecutorOutput().contains("API_CALL_CHAIN")
                        && step.getExecutorOutput().contains("controller")));
        Assertions.assertTrue(task.getArtifacts().stream()
                .filter(artifact -> "MODULE_SUMMARY".equals(artifact.getArtifactType()))
                .anyMatch(artifact -> String.valueOf(artifact.getStructuredContent()).contains("API_CALL_CHAIN")
                        && artifact.getStructuredContent().get("callChain") != null));
    }

    @Test
    void reviewerShouldRejectDiagnosisWithoutEvidenceRefs() {
        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .stepId(4L)
                .repoId("repo_bug_test")
                .userGoal("login token exception controller")
                .previousSteps(List.of(AgentStepSummary.builder()
                        .stepId(3L)
                        .stepSeq(3)
                        .stepTitle("分析可能原因")
                        .assignedAgent("DiagnosisAgent")
                        .summary("Possible token validation issue")
                        .structuredOutput(Map.of("hypotheses", List.of()))
                        .evidenceRefs(List.of())
                        .confidence(0.6D)
                        .nextAction("CONTINUE")
                        .failureReason(null)
                        .build()))
                .evidenceRefs(List.of())
                .memory(Map.of())
                .build();

        var result = reviewerAgent.run(context);

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(NextAction.REPLAN, result.getNextAction());
    }

    @Test
    void reviewerShouldRejectHypothesisWithoutAnchoredEvidence() {
        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .stepId(4L)
                .repoId("repo_bug_test")
                .userGoal("login token exception controller")
                .previousSteps(List.of(AgentStepSummary.builder()
                        .stepId(3L)
                        .stepSeq(3)
                        .stepTitle("分析可能原因")
                        .assignedAgent("DiagnosisAgent")
                        .summary("Possible token validation issue")
                        .structuredOutput(Map.of(
                                "summary", "Possible token validation issue",
                                "hypotheses", List.of(Map.of(
                                        "cause", "token validation branch misses a safe fallback",
                                        "evidence", List.of("LoginController.java"),
                                        "confidence", 0.82D,
                                        "risk", "needs runtime confirmation"
                                )),
                                "needMoreSearch", false
                        ))
                        .evidenceRefs(List.of())
                        .confidence(0.82D)
                        .nextAction("CONTINUE")
                        .failureReason(null)
                        .build()))
                .evidenceRefs(List.of(com.yupi.yuaiagent.task.dto.EvidenceRefDto.builder()
                        .repoId("repo_bug_test")
                        .chunkId("chunk_bug_test")
                        .filePath("src/main/java/com/example/LoginController.java")
                        .startLine(35)
                        .endLine(78)
                        .reason("Matched token validation exception")
                        .build()))
                .memory(Map.of())
                .build();

        var result = reviewerAgent.run(context);

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(NextAction.REPLAN, result.getNextAction());
    }

    @Test
    void reviewerShouldFallbackToRuleBasedReviewWhenLlmReviewFails() {
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(ReviewerDecisionDto.class)))
                .thenReturn(LlmStructuredResponse.<ReviewerDecisionDto>builder()
                        .success(false)
                        .status(LlmCallStatus.FAILED)
                        .errorMessage("temporary review failure")
                        .build());

        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .stepId(4L)
                .repoId("repo_bug_test")
                .userGoal("login token exception controller")
                .previousSteps(List.of(AgentStepSummary.builder()
                        .stepId(3L)
                        .stepSeq(3)
                        .stepTitle("分析可能原因")
                        .assignedAgent("DiagnosisAgent")
                        .summary("Possible token validation issue")
                        .structuredOutput(Map.of(
                                "summary", "Possible token validation issue",
                                "hypotheses", List.of(Map.of(
                                        "cause", "token validation branch misses a safe fallback",
                                        "evidence", List.of("src/main/java/com/example/LoginController.java:35-78"),
                                        "confidence", 0.82D,
                                        "risk", "needs runtime confirmation"
                                )),
                                "needMoreSearch", false
                        ))
                        .evidenceRefs(List.of())
                        .confidence(0.82D)
                        .nextAction("CONTINUE")
                        .failureReason(null)
                        .build()))
                .evidenceRefs(List.of(com.yupi.yuaiagent.task.dto.EvidenceRefDto.builder()
                        .repoId("repo_bug_test")
                        .chunkId("chunk_bug_test")
                        .filePath("src/main/java/com/example/LoginController.java")
                        .startLine(35)
                        .endLine(78)
                        .reason("Matched token validation exception")
                        .build()))
                .memory(Map.of())
                .build();

        var result = reviewerAgent.run(context);

        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals(NextAction.DELIVER, result.getNextAction());
        Assertions.assertTrue(String.valueOf(result.getStructuredOutput().get("reviewerReason"))
                .contains("LLM review failed, fallback to rule-based review"));
    }

    @Test
    void shouldBlockTaskWhenSameReplanReasonRepeatsTooManyTimes() {
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(DiagnosisOutputDto.class)))
                .thenReturn(LlmStructuredResponse.<DiagnosisOutputDto>builder()
                        .success(true)
                        .status(LlmCallStatus.SUCCESS)
                        .data(DiagnosisOutputDto.builder()
                                .summary("Current evidence is still insufficient to reach a grounded diagnosis.")
                                .hypotheses(List.of())
                                .needMoreSearch(true)
                                .missingInfo(List.of("Need more runtime evidence"))
                                .build())
                        .content("{\"summary\":\"Current evidence is still insufficient to reach a grounded diagnosis.\"}")
                        .latencyMs(10L)
                        .build());

        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("BUG_DIAGNOSIS");
        request.setGoal("login token exception controller");

        AgentTaskResponse created = agentTaskService.createTask(request);

        codebaseAgentOrchestratorService.runTask(created.getTaskId());

        AgentTaskResponse task = agentTaskService.getTask(created.getTaskId());

        Assertions.assertEquals("BLOCKED", task.getStatus());
        Assertions.assertEquals("NEED_HUMAN_APPROVAL", task.getReviewSuggestedAction());
        Assertions.assertTrue(task.getErrorMessage().contains("repeated replan limit"));
        Assertions.assertEquals(4, task.getReplanCount());
        Assertions.assertEquals(4, task.getConsecutiveSameReasonReplanCount());
        Assertions.assertTrue(task.getEvents().stream()
                .anyMatch(event -> "TASK_BLOCKED".equals(event.getEventType())
                        && event.getEventContent().contains("repeated replan limit")));
    }

    @Test
    void replanShouldResetRoundStateForFreshWorkflow() {
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setRepoId("repo_bug_test");
        request.setBusinessType("BUG_DIAGNOSIS");
        request.setGoal("login token exception controller");

        AgentTaskResponse created = agentTaskService.createTask(request);
        codebaseAgentOrchestratorService.ensureInitialPlan(created.getTaskId());

        var task = agentTaskRepository.findById(created.getTaskId()).orElseThrow();
        task.setCurrentRound(task.getMaxRound());
        task.setStatus(com.yupi.yuaiagent.task.enums.AgentTaskStatus.BLOCKED);
        task.setErrorMessage("Task exceeded maxRound and now requires human intervention.");
        task.setReviewSummary("blocked");
        task.setReviewSuggestedAction("NEED_HUMAN_APPROVAL");
        task.setReviewSuggestedStepSeq(2);
        task.setReplanCount(4);
        task.setConsecutiveSameReasonReplanCount(4);
        task.setLastReplanReason("Evidence is insufficient and more code search is required.");
        agentTaskRepository.save(task);

        codebaseAgentOrchestratorService.manualReplanTask(created.getTaskId());

        var replanned = agentTaskRepository.findById(created.getTaskId()).orElseThrow();
        Assertions.assertEquals(0, replanned.getCurrentRound());
        Assertions.assertEquals("RUNNING", replanned.getStatus().name());
        Assertions.assertNull(replanned.getErrorMessage());
        Assertions.assertNull(replanned.getReviewSummary());
        Assertions.assertNull(replanned.getReviewSuggestedAction());
        Assertions.assertNull(replanned.getReviewSuggestedStepSeq());
        Assertions.assertEquals(0, replanned.getReplanCount());
        Assertions.assertEquals(0, replanned.getConsecutiveSameReasonReplanCount());
        Assertions.assertNull(replanned.getLastReplanReason());
    }
}
