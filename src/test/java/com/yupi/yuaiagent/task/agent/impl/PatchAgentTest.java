package com.yupi.yuaiagent.task.agent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.llm.LlmService;
import com.yupi.yuaiagent.task.agent.AgentContext;
import com.yupi.yuaiagent.task.agent.AgentStepSummary;
import com.yupi.yuaiagent.task.agent.NextAction;
import com.yupi.yuaiagent.task.dto.EvidenceRefDto;
import com.yupi.yuaiagent.task.repository.AgentTaskRepository;
import com.yupi.yuaiagent.task.service.AgentArtifactService;
import com.yupi.yuaiagent.task.service.AgentTaskWorkspaceService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

class PatchAgentTest {

    private final LlmService llmService = Mockito.mock(LlmService.class);
    private final AgentTaskRepository agentTaskRepository = Mockito.mock(AgentTaskRepository.class);
    private final AgentTaskWorkspaceService workspaceService = Mockito.mock(AgentTaskWorkspaceService.class);
    private final AgentArtifactService artifactService = Mockito.mock(AgentArtifactService.class);
    private final PatchAgent patchAgent = new PatchAgent(
            llmService,
            new ObjectMapper(),
            agentTaskRepository,
            workspaceService,
            artifactService
    );

    @Test
    void shouldRequestHumanApprovalWhenDiagnosisIsMissing() {
        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .stepId(5L)
                .repoId("repo_bug_test")
                .businessType("PATCH_SUGGESTION")
                .userGoal("Generate a safe patch plan")
                .previousSteps(List.of())
                .evidenceRefs(List.of())
                .memory(Map.of())
                .build();

        var result = patchAgent.run(context);

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(NextAction.NEED_HUMAN_APPROVAL, result.getNextAction());
    }

    @Test
    void shouldReplanWhenEvidenceRefsAreMissing() {
        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .stepId(5L)
                .repoId("repo_bug_test")
                .businessType("PATCH_SUGGESTION")
                .userGoal("Generate a safe patch plan")
                .previousSteps(List.of(AgentStepSummary.builder()
                        .stepId(3L)
                        .stepSeq(3)
                        .stepTitle("分析可能原因")
                        .assignedAgent("DiagnosisAgent")
                        .summary("Token validation branch may throw an uncaught exception.")
                        .structuredOutput(Map.of(
                                "summary", "Token validation branch may throw an uncaught exception.",
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
                .evidenceRefs(List.of())
                .memory(Map.of())
                .build();

        var result = patchAgent.run(context);

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(NextAction.REPLAN, result.getNextAction());
        Assertions.assertTrue(result.getFailureReason().contains("grounded evidence"));
    }
}
