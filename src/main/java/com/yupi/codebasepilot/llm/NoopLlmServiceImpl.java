package com.yupi.codebasepilot.llm;

import com.yupi.codebasepilot.llm.dto.LlmCallStatus;
import com.yupi.codebasepilot.llm.dto.LlmRequest;
import com.yupi.codebasepilot.llm.dto.LlmResponse;
import com.yupi.codebasepilot.llm.dto.LlmStructuredResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

@Service
@Conditional(NoopLlmEnabledCondition.class)
public class NoopLlmServiceImpl implements LlmService {

    private final LlmProperties llmProperties;
    private final String dashscopeApiKey;
    private final String deepseekApiKey;

    public NoopLlmServiceImpl(LlmProperties llmProperties,
                              @Value("${spring.ai.dashscope.api-key:}") String dashscopeApiKey,
                              @Value("${llm.deepseek.api-key:${DEEPSEEK_API_KEY:}}") String deepseekApiKey) {
        this.llmProperties = llmProperties;
        this.dashscopeApiKey = dashscopeApiKey;
        this.deepseekApiKey = deepseekApiKey;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long startedAt = System.currentTimeMillis();
        String errorMessage = LlmAvailability.unavailableReason(
                llmProperties,
                resolveConfiguredApiKey()
        );
        long latencyMs = System.currentTimeMillis() - startedAt;
        return LlmResponse.builder()
                .success(false)
                .status(LlmCallStatus.DISABLED)
                .content(null)
                .model(resolveModel())
                .latencyMs(latencyMs)
                .promptTokens(null)
                .completionTokens(null)
                .totalTokens(null)
                .errorMessage(errorMessage)
                .rawResponse(null)
                .build();
    }

    @Override
    public <T> LlmStructuredResponse<T> chatForObject(LlmRequest request, Class<T> responseType) {
        LlmResponse response = chat(request);
        return LlmStructuredResponse.<T>builder()
                .success(false)
                .status(response.getStatus())
                .data(null)
                .content(response.getContent())
                .model(response.getModel())
                .latencyMs(response.getLatencyMs())
                .promptTokens(response.getPromptTokens())
                .completionTokens(response.getCompletionTokens())
                .totalTokens(response.getTotalTokens())
                .errorMessage(response.getErrorMessage())
                .rawResponse(response.getRawResponse())
                .build();
    }

    private String resolveModel() {
        String provider = llmProperties.getProvider();
        return provider == null || provider.isBlank() ? "disabled" : provider + "-disabled";
    }

    private String resolveConfiguredApiKey() {
        String provider = llmProperties.getProvider() == null ? "" : llmProperties.getProvider().trim().toLowerCase();
        if ("deepseek".equals(provider)) {
            return deepseekApiKey;
        }
        return dashscopeApiKey;
    }
}
