package com.yupi.codebasepilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.codebasepilot.llm.dto.LlmCallStatus;
import com.yupi.codebasepilot.llm.dto.LlmRequest;
import com.yupi.codebasepilot.llm.dto.LlmResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

class DeepSeekLlmServiceImplTest {

    @Test
    void shouldReturnDisabledWhenDeepSeekIsNotConfigured() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setProvider("deepseek");

        DeepSeekLlmServiceImpl service = new DeepSeekLlmServiceImpl(
                RestClient.builder(),
                properties,
                new LlmJsonParser(new ObjectMapper()),
                new ObjectMapper(),
                "https://api.deepseek.com",
                "deepseek-chat",
                ""
        );

        LlmResponse response = service.chat(LlmRequest.builder()
                .userPrompt("hello")
                .build());

        Assertions.assertFalse(response.isSuccess());
        Assertions.assertEquals(LlmCallStatus.DISABLED, response.getStatus());
        Assertions.assertEquals("LLM is disabled or DEEPSEEK_API_KEY is not configured", response.getErrorMessage());
    }

    @Test
    void shouldParseSuccessfulDeepSeekResponse() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setProvider("deepseek");
        properties.setRetryTimes(0);
        properties.setTimeoutSeconds(1);

        DeepSeekLlmServiceImpl service = new DeepSeekLlmServiceImpl(
                RestClient.builder(),
                properties,
                new LlmJsonParser(new ObjectMapper()),
                new ObjectMapper(),
                "https://api.deepseek.com",
                "deepseek-chat",
                "test-key"
        ) {
            @Override
            protected String doChatCompletion(Map<String, Object> payload) {
                return """
                        {
                          "model": "deepseek-chat",
                          "choices": [
                            {
                              "message": {
                                "content": "{\\"summary\\":\\"ok\\"}"
                              }
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 12,
                            "completion_tokens": 8,
                            "total_tokens": 20
                          }
                        }
                        """;
            }
        };

        LlmResponse response = service.chat(LlmRequest.builder()
                .userPrompt("hello")
                .build());

        Assertions.assertTrue(response.isSuccess());
        Assertions.assertEquals(LlmCallStatus.SUCCESS, response.getStatus());
        Assertions.assertEquals("{\"summary\":\"ok\"}", response.getContent());
        Assertions.assertEquals("deepseek-chat", response.getModel());
        Assertions.assertEquals(12, response.getPromptTokens());
        Assertions.assertEquals(8, response.getCompletionTokens());
        Assertions.assertEquals(20, response.getTotalTokens());
    }
}
