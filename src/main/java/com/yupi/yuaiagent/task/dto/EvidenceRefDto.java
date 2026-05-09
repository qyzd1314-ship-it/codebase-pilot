package com.yupi.yuaiagent.task.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class EvidenceRefDto {

    String repoId;
    String chunkId;
    String filePath;
    Integer startLine;
    Integer endLine;
    Double score;
    String reason;
    String codePreview;
}
