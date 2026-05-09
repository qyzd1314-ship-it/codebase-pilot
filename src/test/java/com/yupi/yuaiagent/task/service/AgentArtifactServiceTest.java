package com.yupi.yuaiagent.task.service;

import com.yupi.yuaiagent.task.dto.AgentArtifactDto;
import com.yupi.yuaiagent.task.dto.EvidenceRefDto;
import com.yupi.yuaiagent.task.dto.RootCauseHypothesisDto;
import com.yupi.yuaiagent.task.dto.RootCauseReportDto;
import com.yupi.yuaiagent.task.entity.AgentTask;
import com.yupi.yuaiagent.task.enums.AgentArtifactType;
import com.yupi.yuaiagent.task.enums.AgentTaskStatus;
import com.yupi.yuaiagent.task.repository.AgentTaskRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import cn.hutool.core.io.FileUtil;

@SpringBootTest
@Transactional
class AgentArtifactServiceTest {

    @Autowired
    private AgentArtifactService agentArtifactService;

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Autowired
    private AgentTaskWorkspaceService agentTaskWorkspaceService;

    private AgentTask task;

    @BeforeEach
    void setUp() {
        agentTaskRepository.deleteAll();
        AgentTask agentTask = new AgentTask();
        agentTask.setTaskNo("task_artifact_test");
        agentTask.setTitle("Artifact test");
        agentTask.setGoal("Validate artifact structured content and evidence refs.");
        agentTask.setTaskType("GENERAL_AGENT");
        agentTask.setStatus(AgentTaskStatus.PENDING);
        agentTask.setAutoApproveLowRisk(true);
        agentTaskWorkspaceService.initializeWorkspace(agentTask);
        this.task = agentTaskRepository.save(agentTask);
    }

    @Test
    void shouldPersistRootCauseReportAndEvidenceRefs() {
        File artifactFile = agentTaskWorkspaceService.resolveWorkspaceFile(task, "artifacts/root-cause-report.json");
        FileUtil.writeString("{\"summary\":\"demo\"}", artifactFile, StandardCharsets.UTF_8);

        List<EvidenceRefDto> evidenceRefs = List.of(EvidenceRefDto.builder()
                .repoId("repo_demo")
                .chunkId("chunk_demo")
                .filePath("src/main/java/AuthFilter.java")
                .startLine(42)
                .endLine(58)
                .score(0.86)
                .reason("token validation missing fallback")
                .codePreview("if (token == null) { throw new IllegalArgumentException(); }")
                .build());

        RootCauseReportDto report = RootCauseReportDto.builder()
                .summary("登录接口 500 可能与 token 校验异常有关")
                .hypotheses(List.of(RootCauseHypothesisDto.builder()
                        .cause("token 为空未兜底")
                        .evidence(List.of("AuthFilter.java:42-58"))
                        .confidence(0.78)
                        .build()))
                .risk("需要结合运行日志进一步确认")
                .nextSteps(List.of("补充 token 为空的单测", "检查异常处理链路"))
                .build();

        agentArtifactService.upsertRootCauseReportArtifact(
                task.getId(),
                1L,
                "root-cause-report.json",
                "artifacts/root-cause-report.json",
                "Structured root cause report",
                report,
                evidenceRefs,
                artifactFile
        );

        List<AgentArtifactDto> artifacts = agentArtifactService.listArtifacts(task.getId());

        Assertions.assertEquals(1, artifacts.size());
        AgentArtifactDto artifact = artifacts.getFirst();
        Assertions.assertEquals(AgentArtifactType.ROOT_CAUSE_REPORT.name(), artifact.getArtifactType());
        Assertions.assertNotNull(artifact.getStructuredContent());
        Assertions.assertEquals("登录接口 500 可能与 token 校验异常有关", artifact.getStructuredContent().get("summary").asText());
        Assertions.assertEquals(1, artifact.getEvidenceRefs().size());
        Assertions.assertEquals("src/main/java/AuthFilter.java", artifact.getEvidenceRefs().getFirst().getFilePath());
    }

    @Test
    void shouldPersistCodeEvidenceArtifact() {
        File artifactFile = agentTaskWorkspaceService.resolveWorkspaceFile(task, "artifacts/code-evidence.json");
        FileUtil.writeString("{\"evidence\":\"demo\"}", artifactFile, StandardCharsets.UTF_8);

        List<EvidenceRefDto> evidenceRefs = List.of(EvidenceRefDto.builder()
                .repoId("repo_demo")
                .chunkId("chunk_login")
                .filePath("src/main/java/LoginController.java")
                .startLine(35)
                .endLine(78)
                .score(0.91)
                .reason("命中 login、token、500 等关键词")
                .codePreview("public Result login(LoginRequest request) { ... }")
                .build());

        agentArtifactService.upsertCodeEvidenceArtifact(
                task.getId(),
                2L,
                "code-evidence.json",
                "artifacts/code-evidence.json",
                "Collected code evidence",
                java.util.Map.of(
                        "summary", "登录和 token 校验相关证据",
                        "generatedAt", LocalDateTime.now().toString()
                ),
                evidenceRefs,
                artifactFile
        );

        AgentArtifactDto artifact = agentArtifactService.listArtifacts(task.getId()).getFirst();

        Assertions.assertEquals(AgentArtifactType.CODE_EVIDENCE.name(), artifact.getArtifactType());
        Assertions.assertNotNull(artifact.getStructuredContent());
        Assertions.assertEquals("登录和 token 校验相关证据", artifact.getStructuredContent().get("summary").asText());
        Assertions.assertEquals(1, artifact.getEvidenceRefs().size());
        Assertions.assertEquals(35, artifact.getEvidenceRefs().getFirst().getStartLine());
        Assertions.assertEquals(78, artifact.getEvidenceRefs().getFirst().getEndLine());
        Assertions.assertEquals("命中 login、token、500 等关键词", artifact.getEvidenceRefs().getFirst().getReason());
    }
}
