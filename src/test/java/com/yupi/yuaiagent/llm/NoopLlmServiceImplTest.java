package com.yupi.yuaiagent.llm;

import com.yupi.yuaiagent.llm.dto.LlmRequest;
import com.yupi.yuaiagent.llm.dto.LlmResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NoopLlmServiceImplTest {

    @Test
    void shouldReturnDisabledResponseWhenApiKeyIsMissing() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setProvider("dashscope");
        NoopLlmServiceImpl service = new NoopLlmServiceImpl(properties, "", "");

        LlmResponse response = service.chat(LlmRequest.builder()
                .userPrompt("hello")
                .build());

        Assertions.assertFalse(response.isSuccess());
        Assertions.assertEquals("LLM is disabled or DASHSCOPE_API_KEY is not configured", response.getErrorMessage());
        Assertions.assertNotNull(response.getLatencyMs());
    }
}
