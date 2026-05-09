package com.yupi.codebasepilot.task.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.codebasepilot.task.dto.AgentArtifactDto;
import com.yupi.codebasepilot.task.dto.EvidenceRefDto;
import com.yupi.codebasepilot.task.dto.ModuleSummaryOutputDto;
import com.yupi.codebasepilot.task.dto.PatchPlanOutputDto;
import com.yupi.codebasepilot.task.dto.RootCauseReportDto;
import com.yupi.codebasepilot.task.entity.AgentArtifact;
import com.yupi.codebasepilot.task.entity.AgentTask;
import com.yupi.codebasepilot.task.enums.AgentArtifactType;
import com.yupi.codebasepilot.task.repository.AgentArtifactRepository;
import com.yupi.codebasepilot.task.repository.AgentTaskRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class AgentArtifactService {

    private final AgentArtifactRepository agentArtifactRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final AgentTaskWorkspaceService agentTaskWorkspaceService;
    private final ObjectMapper objectMapper;

    public AgentArtifactService(AgentArtifactRepository agentArtifactRepository,
                                AgentTaskRepository agentTaskRepository,
                                AgentTaskWorkspaceService agentTaskWorkspaceService,
                                ObjectMapper objectMapper) {
        this.agentArtifactRepository = agentArtifactRepository;
        this.agentTaskRepository = agentTaskRepository;
        this.agentTaskWorkspaceService = agentTaskWorkspaceService;
        this.objectMapper = objectMapper;
    }

    public AgentArtifact upsertArtifact(Long taskId,
                                        Long stepId,
                                        String artifactType,
                                        String artifactName,
                                        String relativePath,
                                        String contentType,
                                        String description,
                                        File artifactFile) {
        return upsertArtifact(taskId, stepId, artifactType, artifactName, relativePath, contentType,
                description, null, null, List.of(), artifactFile);
    }

    public AgentArtifact upsertArtifact(Long taskId,
                                        Long stepId,
                                        String artifactType,
                                        String artifactName,
                                        String relativePath,
                                        String contentType,
                                        String description,
                                        Object structuredContent,
                                        Object metadata,
                                        List<EvidenceRefDto> evidenceRefs,
                                        File artifactFile) {
        AgentArtifact artifact = agentArtifactRepository.findByTaskIdAndRelativePath(taskId, relativePath)
                .orElseGet(AgentArtifact::new);
        artifact.setTaskId(taskId);
        artifact.setStepId(stepId);
        artifact.setArtifactType(artifactType);
        artifact.setArtifactName(artifactName);
        artifact.setRelativePath(relativePath);
        artifact.setContentType(contentType);
        artifact.setDescription(description);
        artifact.setStructuredContent(writeJson(structuredContent));
        artifact.setMetadata(writeJson(metadata));
        artifact.setEvidenceRefs(writeJson(evidenceRefs));
        artifact.setSizeBytes(artifactFile != null && artifactFile.exists() ? artifactFile.length() : 0L);
        return agentArtifactRepository.save(artifact);
    }

    public AgentArtifact upsertRootCauseReportArtifact(Long taskId,
                                                       Long stepId,
                                                       String artifactName,
                                                       String relativePath,
                                                       String description,
                                                       RootCauseReportDto report,
                                                       List<EvidenceRefDto> evidenceRefs,
                                                       File artifactFile) {
        return upsertArtifact(taskId,
                stepId,
                AgentArtifactType.ROOT_CAUSE_REPORT.name(),
                artifactName,
                relativePath,
                "application/json",
                description,
                report,
                null,
                evidenceRefs,
                artifactFile);
    }

    public AgentArtifact upsertCodeEvidenceArtifact(Long taskId,
                                                    Long stepId,
                                                    String artifactName,
                                                    String relativePath,
                                                    String description,
                                                    Object structuredContent,
                                                    List<EvidenceRefDto> evidenceRefs,
                                                    File artifactFile) {
        return upsertArtifact(taskId,
                stepId,
                AgentArtifactType.CODE_EVIDENCE.name(),
                artifactName,
                relativePath,
                "application/json",
                description,
                structuredContent,
                null,
                evidenceRefs,
                artifactFile);
    }

    public AgentArtifact upsertPatchPlanArtifact(Long taskId,
                                                 Long stepId,
                                                 String artifactName,
                                                 String relativePath,
                                                 String description,
                                                 PatchPlanOutputDto patchPlan,
                                                 List<EvidenceRefDto> evidenceRefs,
                                                 File artifactFile) {
        return upsertArtifact(taskId,
                stepId,
                AgentArtifactType.PATCH_PLAN.name(),
                artifactName,
                relativePath,
                "application/json",
                description,
                patchPlan,
                null,
                evidenceRefs,
                artifactFile);
    }

    public AgentArtifact upsertModuleSummaryArtifact(Long taskId,
                                                     Long stepId,
                                                     String artifactName,
                                                     String relativePath,
                                                     String description,
                                                     ModuleSummaryOutputDto moduleSummary,
                                                     List<EvidenceRefDto> evidenceRefs,
                                                     File artifactFile) {
        return upsertArtifact(taskId,
                stepId,
                AgentArtifactType.MODULE_SUMMARY.name(),
                artifactName,
                relativePath,
                "application/json",
                description,
                moduleSummary,
                null,
                evidenceRefs,
                artifactFile);
    }

    public List<AgentArtifactDto> listArtifacts(Long taskId) {
        return agentArtifactRepository.findByTaskIdOrderByCreatedAtDesc(taskId)
                .stream()
                .map(artifact -> AgentArtifactDto.builder()
                        .id(artifact.getId())
                        .stepId(artifact.getStepId())
                        .artifactType(artifact.getArtifactType())
                        .artifactName(artifact.getArtifactName())
                        .relativePath(artifact.getRelativePath())
                        .contentType(artifact.getContentType())
                        .description(artifact.getDescription())
                        .structuredContent(readJsonNode(artifact.getStructuredContent()))
                        .metadata(readJsonNode(artifact.getMetadata()))
                        .evidenceRefs(readEvidenceRefs(artifact.getEvidenceRefs()))
                        .sizeBytes(artifact.getSizeBytes())
                        .previewable(isPreviewable(artifact))
                        .createdAt(artifact.getCreatedAt())
                        .updatedAt(artifact.getUpdatedAt())
                        .build())
                .toList();
    }

    public ArtifactFilePayload getArtifactFile(Long taskId, Long artifactId) {
        AgentArtifact artifact = agentArtifactRepository.findByIdAndTaskId(artifactId, taskId)
                .orElseThrow(() -> new EntityNotFoundException("Artifact not found: " + artifactId));
        AgentTask task = agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Task not found: " + taskId));
        File file = agentTaskWorkspaceService.resolveWorkspaceFile(task, artifact.getRelativePath());
        if (!file.exists() || !file.isFile()) {
            throw new EntityNotFoundException("Artifact file not found: " + artifact.getRelativePath());
        }
        return new ArtifactFilePayload(artifact, file);
    }

    public String readArtifactContent(Long taskId, Long artifactId) {
        ArtifactFilePayload payload = getArtifactFile(taskId, artifactId);
        if (!isPreviewable(payload.artifact())) {
            throw new IllegalStateException("Artifact is not previewable: " + payload.artifact().getArtifactName());
        }
        return FileUtil.readString(payload.file(), StandardCharsets.UTF_8);
    }

    private boolean isPreviewable(AgentArtifact artifact) {
        String contentType = StrUtil.blankToDefault(artifact.getContentType(), "");
        String lowerContentType = contentType.toLowerCase();
        String lowerName = StrUtil.blankToDefault(artifact.getArtifactName(), "").toLowerCase();
        return lowerContentType.startsWith("text/")
                || lowerContentType.contains("json")
                || lowerContentType.contains("xml")
                || lowerContentType.contains("markdown")
                || lowerName.endsWith(".txt")
                || lowerName.endsWith(".md")
                || lowerName.endsWith(".json")
                || lowerName.endsWith(".xml");
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize artifact structured payload.", e);
        }
    }

    private JsonNode readJsonNode(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse artifact structured payload.", e);
        }
    }

    private List<EvidenceRefDto> readEvidenceRefs(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EvidenceRefDto>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse artifact evidence refs.", e);
        }
    }

    public record ArtifactFilePayload(AgentArtifact artifact, File file) {
    }
}
