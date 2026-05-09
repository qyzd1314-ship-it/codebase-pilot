package com.yupi.yuaiagent.task.agent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.llm.LlmService;
import com.yupi.yuaiagent.llm.dto.LlmCallStatus;
import com.yupi.yuaiagent.llm.dto.LlmStructuredResponse;
import com.yupi.yuaiagent.task.agent.AgentContext;
import com.yupi.yuaiagent.task.agent.AgentResult;
import com.yupi.yuaiagent.task.agent.AgentStepSummary;
import com.yupi.yuaiagent.task.agent.NextAction;
import com.yupi.yuaiagent.task.dto.DiagnosisHypothesisDto;
import com.yupi.yuaiagent.task.dto.DiagnosisOutputDto;
import com.yupi.yuaiagent.task.dto.EvidenceRefDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

class DiagnosisAgentTest {

    private final LlmService llmService = Mockito.mock(LlmService.class);
    private final DiagnosisAgent diagnosisAgent = new DiagnosisAgent(llmService, new ObjectMapper());

    @Test
    void shouldReplanWhenEvidenceIsMissing() {
        AgentResult result = diagnosisAgent.run(AgentContext.builder()
                .taskId(1L)
                .repoId("repo_1")
                .userGoal("find bug")
                .previousSteps(List.of())
                .evidenceRefs(List.of())
                .memory(Map.of())
                .build());

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(NextAction.REPLAN, result.getNextAction());
        Assertions.assertEquals("Missing code evidence, so a grounded diagnosis cannot be generated.", result.getFailureReason());
        Mockito.verifyNoInteractions(llmService);
    }

    @Test
    void shouldReturnHypothesesWhenLlmSucceeds() {
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(DiagnosisOutputDto.class)))
                .thenReturn(LlmStructuredResponse.<DiagnosisOutputDto>builder()
                        .success(true)
                        .status(LlmCallStatus.SUCCESS)
                        .data(DiagnosisOutputDto.builder()
                                .summary("Diagnosis grounded in code evidence.")
                                .hypotheses(List.of(DiagnosisHypothesisDto.builder()
                                        .cause("token validation branch misses fallback handling")
                                        .evidence(List.of("src/AuthFilter.java:42-58"))
                                        .confidence(0.78D)
                                        .risk("needs runtime confirmation")
                                        .build()))
                                .needMoreSearch(false)
                                .missingInfo(List.of())
                                .build())
                        .content("{...}")
                        .latencyMs(100L)
                        .build());

        AgentResult result = diagnosisAgent.run(buildContext());

        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals(NextAction.CONTINUE, result.getNextAction());
        Assertions.assertNotNull(result.getStructuredOutput());
        Assertions.assertTrue(result.getStructuredOutput().containsKey("hypotheses"));
        Assertions.assertEquals(1, result.getEvidenceRefs().size());
    }

    @Test
    void shouldReplanWhenLlmNeedsMoreSearch() {
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(DiagnosisOutputDto.class)))
                .thenReturn(LlmStructuredResponse.<DiagnosisOutputDto>builder()
                        .success(true)
                        .status(LlmCallStatus.SUCCESS)
                        .data(DiagnosisOutputDto.builder()
                                .summary("Need more evidence.")
                                .hypotheses(List.of())
                                .needMoreSearch(true)
                                .missingInfo(List.of("Need controller exception handler"))
                                .build())
                        .content("{...}")
                        .latencyMs(50L)
                        .build());

        AgentResult result = diagnosisAgent.run(buildContext());

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(NextAction.REPLAN, result.getNextAction());
        Assertions.assertEquals("Evidence is insufficient and more code search is required.", result.getFailureReason());
    }

    @Test
    void shouldReturnNeedHumanApprovalWhenJsonParsingFails() {
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(DiagnosisOutputDto.class)))
                .thenReturn(LlmStructuredResponse.<DiagnosisOutputDto>builder()
                        .success(false)
                        .status(LlmCallStatus.FAILED)
                        .content("not valid json but some raw answer")
                        .errorMessage("parse failed")
                        .latencyMs(120L)
                        .build());

        AgentResult result = diagnosisAgent.run(buildContext());

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(NextAction.NEED_HUMAN_APPROVAL, result.getNextAction());
        Assertions.assertEquals("LLM output could not be parsed as JSON and requires human review.", result.getFailureReason());
        Assertions.assertEquals("not valid json but some raw answer", result.getStructuredOutput().get("rawContent"));
    }

    @Test
    void shouldRequestHumanApprovalWhenLlmIsDisabled() {
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(DiagnosisOutputDto.class)))
                .thenReturn(LlmStructuredResponse.<DiagnosisOutputDto>builder()
                        .success(false)
                        .status(LlmCallStatus.DISABLED)
                        .content(null)
                        .errorMessage("LLM is disabled or DASHSCOPE_API_KEY is not configured")
                        .latencyMs(1L)
                        .build());

        AgentResult result = diagnosisAgent.run(buildContext());

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(NextAction.NEED_HUMAN_APPROVAL, result.getNextAction());
        Assertions.assertEquals("LLM is unavailable. Configure an API key or review the diagnosis manually.", result.getFailureReason());
        Assertions.assertEquals("DISABLED", result.getStructuredOutput().get("llmStatus"));
        Assertions.assertEquals(1, result.getEvidenceRefs().size());
    }

    @Test
    void shouldRetryWhenLlmCallFails() {
        Mockito.when(llmService.chatForObject(ArgumentMatchers.any(), ArgumentMatchers.eq(DiagnosisOutputDto.class)))
                .thenReturn(LlmStructuredResponse.<DiagnosisOutputDto>builder()
                        .success(false)
                        .status(LlmCallStatus.FAILED)
                        .content(null)
                        .errorMessage("model timeout")
                        .latencyMs(200L)
                        .build());

        AgentResult result = diagnosisAgent.run(buildContext());

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(NextAction.RETRY, result.getNextAction());
        Assertions.assertTrue(result.getFailureReason().contains("LLM call failed"));
    }

    private AgentContext buildContext() {
        return AgentContext.builder()
                .taskId(1L)
                .stepId(2L)
                .repoId("repo_1")
                .userGoal("The login endpoint intermittently returns 500. Diagnose the likely cause.")
                .previousSteps(List.of(AgentStepSummary.builder()
                        .stepId(1L)
                        .stepSeq(1)
                        .stepTitle("Search related code")
                        .assignedAgent("CodeSearchAgent")
                        .summary("Found related code evidence.")
                        .evidenceRefs(List.of())
                        .nextAction("CONTINUE")
                        .build()))
                .evidenceRefs(List.of(EvidenceRefDto.builder()
                        .repoId("repo_1")
                        .chunkId("chunk_1")
                        .filePath("src/AuthFilter.java")
                        .startLine(42)
                        .endLine(58)
                        .score(0.82D)
                        .reason("token validation branch")
                        .codePreview("if (token == null) { throw new IllegalArgumentException(); }")
                        .build()))
                .memory(Map.of())
                .build();
    }
}
