package com.yupi.codebasepilot.repo.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CodeSearchResultDto {

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
