package com.yupi.yuaiagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class LlmJsonParser {

    private final ObjectMapper objectMapper;

    public LlmJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    public <T> T parseObject(String content, Class<T> responseType) {
        try {
            return objectMapper.readValue(extractJson(content), responseType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse structured LLM response: " + e.getMessage(), e);
        }
    }
}
