package com.yupi.yuaiagent.eval.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class EvalRetrievedChunkDto {

    String chunkId;

    String filePath;

    String symbolName;

    Integer startLine;

    Integer endLine;

    Double score;

    String reason;

    String matchSource;

    String contentPreview;
}
