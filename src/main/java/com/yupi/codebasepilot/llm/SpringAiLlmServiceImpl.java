package com.yupi.codebasepilot.llm;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.codebasepilot.llm.dto.LlmCallStatus;
import com.yupi.codebasepilot.llm.dto.LlmMessage;
import com.yupi.codebasepilot.llm.dto.LlmRequest;
import com.yupi.codebasepilot.llm.dto.LlmResponse;
import com.yupi.codebasepilot.llm.dto.LlmStructuredResponse;
import jakarta.annotation.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "true")
@Conditional(DashScopeLlmEnabledCondition.class)
public class SpringAiLlmServiceImpl implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmServiceImpl.class);

    private static final ExecutorService LLM_TIMEOUT_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("llm-timeout-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final ChatModel dashscopeChatModel;
    private final LlmProperties llmProperties;
    private final LlmJsonParser llmJsonParser;
    private final String configuredModel;
    private final String configuredApiKey;

    public SpringAiLlmServiceImpl(@Qualifier("dashscopeChatModel") @Nullable ChatModel dashscopeChatModel,
                                  LlmProperties llmProperties,
                                  LlmJsonParser llmJsonParser,
                                  @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}") String configuredModel,
                                  @Value("${spring.ai.dashscope.api-key:}") String configuredApiKey) {
        this.dashscopeChatModel = dashscopeChatModel;
        this.llmProperties = llmProperties;
        this.llmJsonParser = llmJsonParser;
        this.configuredModel = configuredModel;
        this.configuredApiKey = configuredApiKey;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        LlmRequest effectiveRequest = withDefaults(request);
        if (!llmProperties.isEnabled()) {
            return buildFailureResponse("LLM is disabled or DASHSCOPE_API_KEY is not configured", 0L, LlmCallStatus.DISABLED);
        }
        if (!LlmAvailability.isDashScopeAvailable(llmProperties, configuredApiKey)) {
            return buildFailureResponse("LLM is disabled or DASHSCOPE_API_KEY is not configured", 0L, LlmCallStatus.DISABLED);
        }
        if (dashscopeChatModel == null) {
            return buildFailureResponse("dashscopeChatModel is not available.", 0L, LlmCallStatus.FAILED);
        }

        int retries = Math.max(llmProperties.getRetryTimes(), 0);
        long startedAt = System.currentTimeMillis();
        Exception lastException = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                Prompt prompt = buildPrompt(effectiveRequest);
                ChatResponse chatResponse = executeWithTimeout(prompt);
                AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
                long latencyMs = System.currentTimeMillis() - startedAt;
                return LlmResponse.builder()
                        .success(true)
                        .status(LlmCallStatus.SUCCESS)
                        .content(assistantMessage == null ? "" : assistantMessage.getText())
                        .model(configuredModel)
                        .latencyMs(latencyMs)
                        .promptTokens(null)
                        .completionTokens(null)
                        .totalTokens(null)
                        .errorMessage(null)
                        .rawResponse(chatResponse == null ? null : chatResponse.toString())
                        .build();
            } catch (Exception e) {
                lastException = e;
            }
        }
        long latencyMs = System.currentTimeMillis() - startedAt;
        return buildFailureResponse(
                "LLM call failed: " + (lastException == null ? "unknown error" : lastException.getMessage()),
                latencyMs,
                LlmCallStatus.FAILED
        );
    }

    @Override
    public <T> LlmStructuredResponse<T> chatForObject(LlmRequest request, Class<T> responseType) {
        LlmRequest structuredRequest = withStructuredHint(request);
        LlmResponse response = chat(structuredRequest);
        if (!response.isSuccess()) {
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
        try {
            T data = llmJsonParser.parseObject(response.getContent(), responseType);
            return LlmStructuredResponse.<T>builder()
                    .success(true)
                    .status(LlmCallStatus.SUCCESS)
                    .data(data)
                    .content(response.getContent())
                    .model(response.getModel())
                    .latencyMs(response.getLatencyMs())
                    .promptTokens(response.getPromptTokens())
                    .completionTokens(response.getCompletionTokens())
                    .totalTokens(response.getTotalTokens())
                    .errorMessage(null)
                    .rawResponse(response.getRawResponse())
                    .build();
        } catch (Exception e) {
            return LlmStructuredResponse.<T>builder()
                    .success(false)
                    .status(LlmCallStatus.FAILED)
                    .data(null)
                    .content(response.getContent())
                    .model(response.getModel())
                    .latencyMs(response.getLatencyMs())
                    .promptTokens(response.getPromptTokens())
                    .completionTokens(response.getCompletionTokens())
                    .totalTokens(response.getTotalTokens())
                    .errorMessage(e.getMessage())
                    .rawResponse(response.getRawResponse())
                    .build();
        }
    }

    private Prompt buildPrompt(LlmRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(new SystemMessage(appendResponseHint(request.getSystemPrompt(), request.getResponseFormatHint())));
        }
        if (request.getMessages() != null) {
            for (LlmMessage message : request.getMessages()) {
                if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                    continue;
                }
                messages.add(convertMessage(message));
            }
        }
        if (request.getUserPrompt() != null && !request.getUserPrompt().isBlank()) {
            messages.add(new UserMessage(request.getUserPrompt()));
        }

        DashScopeChatOptions options = new DashScopeChatOptions();
        BeanWrapper beanWrapper = new BeanWrapperImpl(options);
        if (beanWrapper.isWritableProperty("model")) {
            beanWrapper.setPropertyValue("model", configuredModel);
        }
        if (request.getTemperature() != null && beanWrapper.isWritableProperty("temperature")) {
            beanWrapper.setPropertyValue("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null && beanWrapper.isWritableProperty("maxTokens")) {
            beanWrapper.setPropertyValue("maxTokens", request.getMaxTokens());
        }
        return new Prompt(messages, options);
    }

    protected ChatResponse doChatCompletion(Prompt prompt) {
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel).build();
        return chatClient.prompt(prompt).call().chatResponse();
    }

    private ChatResponse executeWithTimeout(Prompt prompt) throws Exception {
        int timeoutSeconds = Math.max(llmProperties.getTimeoutSeconds(), 1);
        log.info("Starting LLM call with timeoutSeconds={} and model={}", timeoutSeconds, configuredModel);
        Future<ChatResponse> future = LLM_TIMEOUT_EXECUTOR.submit(() -> doChatCompletion(prompt));
        try {
            ChatResponse response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("LLM call completed successfully for model={}", configuredModel);
            return response;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("LLM call timed out after {} seconds for model={}", timeoutSeconds, configuredModel);
            throw new IllegalStateException("LLM call timed out after " + timeoutSeconds + " seconds.", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.error("LLM call was interrupted for model={}", configuredModel, e);
            throw new IllegalStateException("LLM call was interrupted.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("LLM call failed for model={}", configuredModel, cause);
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("LLM call failed.", cause);
        }
    }

    private Message convertMessage(LlmMessage message) {
        String role = message.getRole() == null ? "user" : message.getRole().trim().toLowerCase();
        return switch (role) {
            case "system" -> new SystemMessage(message.getContent());
            case "assistant" -> new AssistantMessage(message.getContent());
            default -> new UserMessage(message.getContent());
        };
    }

    private LlmRequest withDefaults(LlmRequest request) {
        LlmRequest source = request == null ? new LlmRequest() : request;
        return LlmRequest.builder()
                .systemPrompt(source.getSystemPrompt())
                .userPrompt(source.getUserPrompt())
                .messages(source.getMessages())
                .temperature(source.getTemperature() == null ? llmProperties.getDefaultTemperature() : source.getTemperature())
                .maxTokens(source.getMaxTokens() == null ? llmProperties.getDefaultMaxTokens() : source.getMaxTokens())
                .responseFormatHint(source.getResponseFormatHint())
                .scene(source.getScene())
                .metadata(source.getMetadata())
                .build();
    }

    private LlmRequest withStructuredHint(LlmRequest request) {
        LlmRequest source = withDefaults(request);
        String responseHint = source.getResponseFormatHint();
        if (responseHint == null || responseHint.isBlank()) {
            responseHint = "Respond with JSON only.";
        }
        source.setResponseFormatHint(responseHint);
        return source;
    }

    private String appendResponseHint(String systemPrompt, String responseFormatHint) {
        if (responseFormatHint == null || responseFormatHint.isBlank()) {
            return systemPrompt;
        }
        return systemPrompt + System.lineSeparator() + responseFormatHint;
    }

    private LlmResponse buildFailureResponse(String errorMessage, long latencyMs, LlmCallStatus status) {
        return LlmResponse.builder()
                .success(false)
                .status(status)
                .content(null)
                .model(configuredModel)
                .latencyMs(latencyMs)
                .promptTokens(null)
                .completionTokens(null)
                .totalTokens(null)
                .errorMessage(errorMessage)
                .rawResponse(null)
                .build();
    }
}
