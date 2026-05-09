package com.yupi.yuaiagent.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmStructuredResponse<T> {

    private boolean success;

    private LlmCallStatus status;

    private T data;

    private String content;

    private String model;

    private Long latencyMs;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private String errorMessage;

    private Object rawResponse;
}
