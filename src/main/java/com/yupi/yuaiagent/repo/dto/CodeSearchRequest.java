package com.yupi.yuaiagent.repo.dto;

import lombok.Data;

@Data
public class CodeSearchRequest {

    private String query;

    private Integer topK;

    /**
     * Supported values:
     * KEYWORD_ONLY / VECTOR_ONLY / HYBRID
     */
    private String searchMode;
}
