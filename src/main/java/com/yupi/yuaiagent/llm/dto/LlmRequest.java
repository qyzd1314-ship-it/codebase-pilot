package com.yupi.yuaiagent.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    private String systemPrompt;

    private String userPrompt;

    private List<LlmMessage> messages;

    private Double temperature;

    private Integer maxTokens;

    private String responseFormatHint;

    private String scene;

    private Map<String, Object> metadata;
}
