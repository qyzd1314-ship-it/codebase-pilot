package com.yupi.codebasepilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.codebasepilot.llm.dto.LlmRequest;
import com.yupi.codebasepilot.llm.dto.LlmCallStatus;
import com.yupi.codebasepilot.llm.dto.LlmResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiLlmServiceImplTest {

    @Test
    void shouldReturnDisabledWhenLlmIsTurnedOff() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(false);
        properties.setProvider("dashscope");
        SpringAiLlmServiceImpl service = new SpringAiLlmServiceImpl(
                null,
                properties,
                new LlmJsonParser(new ObjectMapper()),
                "qwen-plus",
                ""
        );

        LlmResponse response = service.chat(LlmRequest.builder()
                .userPrompt("hello")
                .build());

        Assertions.assertFalse(response.isSuccess());
        Assertions.assertEquals("LLM is disabled or DASHSCOPE_API_KEY is not configured", response.getErrorMessage());
    }

    @Test
    void shouldReturnFailureWhenLlmCallTimesOut() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setProvider("dashscope");
        properties.setRetryTimes(0);
        properties.setTimeoutSeconds(1);

        SpringAiLlmServiceImpl service = new SpringAiLlmServiceImpl(
                Mockito.mock(ChatModel.class),
                properties,
                new LlmJsonParser(new ObjectMapper()),
                "qwen-max",
                "test-key"
        ) {
            @Override
            protected ChatResponse doChatCompletion(Prompt prompt) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        };

        LlmResponse response = service.chat(LlmRequest.builder()
                .userPrompt("hello")
                .build());

        Assertions.assertFalse(response.isSuccess());
        Assertions.assertEquals(LlmCallStatus.FAILED, response.getStatus());
        Assertions.assertTrue(response.getErrorMessage().contains("timed out after 1 seconds"));
    }
}
