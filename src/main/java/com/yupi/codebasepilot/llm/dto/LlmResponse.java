package com.yupi.codebasepilot.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    private boolean success;

    private LlmCallStatus status;

    private String content;

    private String model;

    private Long latencyMs;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private String errorMessage;

    private Object rawResponse;
}
