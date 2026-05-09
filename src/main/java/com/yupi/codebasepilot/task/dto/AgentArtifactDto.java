package com.yupi.codebasepilot.task.dto;

import lombok.Builder;
import lombok.Value;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class AgentArtifactDto {

    Long id;
    Long stepId;
    String artifactType;
    String artifactName;
    String relativePath;
    String contentType;
    String description;
    JsonNode structuredContent;
    JsonNode metadata;
    List<EvidenceRefDto> evidenceRefs;
    Long sizeBytes;
    Boolean previewable;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
