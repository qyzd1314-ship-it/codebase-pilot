package com.yupi.codebasepilot.controller;

import com.yupi.codebasepilot.llm.LlmService;
import com.yupi.codebasepilot.llm.dto.LlmRequest;
import com.yupi.codebasepilot.llm.dto.LlmResponse;
import com.yupi.codebasepilot.llm.dto.LlmTestRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/llm")
public class LlmController {

    private final LlmService llmService;

    public LlmController(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping("/test")
    public LlmResponse testLlm(@RequestBody(required = false) LlmTestRequest request) {
        String message = request == null ? null : request.getMessage();
        if (message == null || message.isBlank()) {
            return LlmResponse.builder()
                    .success(false)
                    .errorMessage("message must not be blank")
                    .latencyMs(0L)
                    .build();
        }
        LlmRequest llmRequest = LlmRequest.builder()
                .systemPrompt("You are a helpful assistant.")
                .userPrompt(message)
                .scene("TEST")
                .build();
        return llmService.chat(llmRequest);
    }
}
