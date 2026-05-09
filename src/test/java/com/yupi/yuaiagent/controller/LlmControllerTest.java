package com.yupi.yuaiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.llm.LlmService;
import com.yupi.yuaiagent.llm.dto.LlmCallStatus;
import com.yupi.yuaiagent.llm.dto.LlmResponse;
import com.yupi.yuaiagent.llm.dto.LlmTestRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LlmController.class)
class LlmControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LlmService llmService;

    @Test
    void shouldReturnLlmResponse() throws Exception {
        LlmTestRequest request = new LlmTestRequest();
        request.setMessage("hello");
        Mockito.when(llmService.chat(ArgumentMatchers.any()))
                .thenReturn(LlmResponse.builder()
                        .success(true)
                        .status(LlmCallStatus.SUCCESS)
                        .content("hello from model")
                        .model("qwen-plus")
                        .latencyMs(123L)
                        .build());

        mockMvc.perform(post("/llm/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.content").value("hello from model"))
                .andExpect(jsonPath("$.model").value("qwen-plus"))
                .andExpect(jsonPath("$.latencyMs").value(123));
    }

    @Test
    void shouldRejectBlankMessage() throws Exception {
        LlmTestRequest request = new LlmTestRequest();
        request.setMessage(" ");

        mockMvc.perform(post("/llm/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMessage").value("message must not be blank"));
    }
}
