package com.yupi.yuaiagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.llm.dto.LlmCallStatus;
import com.yupi.yuaiagent.llm.dto.LlmMessage;
import com.yupi.yuaiagent.llm.dto.LlmRequest;
import com.yupi.yuaiagent.llm.dto.LlmResponse;
import com.yupi.yuaiagent.llm.dto.LlmStructuredResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "true")
@Conditional(DeepSeekLlmEnabledCondition.class)
public class DeepSeekLlmServiceImpl implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmServiceImpl.class);

    private static final ExecutorService LLM_TIMEOUT_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("deepseek-llm-timeout-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final RestClient restClient;
    private final LlmProperties llmProperties;
    private final LlmJsonParser llmJsonParser;
    private final ObjectMapper objectMapper;
    private final String configuredModel;
    private final String configuredApiKey;

    public DeepSeekLlmServiceImpl(RestClient.Builder restClientBuilder,
                                  LlmProperties llmProperties,
                                  LlmJsonParser llmJsonParser,
                                  ObjectMapper objectMapper,
                                  @Value("${llm.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                                  @Value("${llm.deepseek.chat-model:deepseek-chat}") String configuredModel,
                                  @Value("${llm.deepseek.api-key:${DEEPSEEK_API_KEY:}}") String configuredApiKey) {
        this.restClient = restClientBuilder
                .baseUrl(trimTrailingSlash(baseUrl))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.llmProperties = llmProperties;
        this.llmJsonParser = llmJsonParser;
        this.objectMapper = objectMapper;
        this.configuredModel = configuredModel;
        this.configuredApiKey = configuredApiKey;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        LlmRequest effectiveRequest = withDefaults(request);
        if (!llmProperties.isEnabled()) {
            return buildFailureResponse("LLM is disabled or DEEPSEEK_API_KEY is not configured", 0L, LlmCallStatus.DISABLED);
        }
        if (!LlmAvailability.isDeepSeekAvailable(llmProperties, configuredApiKey)) {
            return buildFailureResponse("LLM is disabled or DEEPSEEK_API_KEY is not configured", 0L, LlmCallStatus.DISABLED);
        }

        int retries = Math.max(llmProperties.getRetryTimes(), 0);
        long startedAt = System.currentTimeMillis();
        Exception lastException = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                Map<String, Object> payload = buildPayload(effectiveRequest);
                String rawResponse = executeWithTimeout(payload);
                long latencyMs = System.currentTimeMillis() - startedAt;
                return parseSuccessResponse(rawResponse, latencyMs);
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

    protected String doChatCompletion(Map<String, Object> payload) {
        return restClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuredApiKey)
                .body(payload)
                .retrieve()
                .body(String.class);
    }

    private String executeWithTimeout(Map<String, Object> payload) throws Exception {
        int timeoutSeconds = Math.max(llmProperties.getTimeoutSeconds(), 1);
        log.info("Starting LLM call with timeoutSeconds={} and model={}", timeoutSeconds, configuredModel);
        Future<String> future = LLM_TIMEOUT_EXECUTOR.submit(() -> doChatCompletion(payload));
        try {
            String response = future.get(timeoutSeconds, TimeUnit.SECONDS);
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

    private LlmResponse parseSuccessResponse(String rawResponse, long latencyMs) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        JsonNode usage = root.path("usage");
        return LlmResponse.builder()
                .success(true)
                .status(LlmCallStatus.SUCCESS)
                .content(content)
                .model(root.path("model").asText(configuredModel))
                .latencyMs(latencyMs)
                .promptTokens(readInteger(usage, "prompt_tokens"))
                .completionTokens(readInteger(usage, "completion_tokens"))
                .totalTokens(readInteger(usage, "total_tokens"))
                .errorMessage(null)
                .rawResponse(rawResponse)
                .build();
    }

    private Map<String, Object> buildPayload(LlmRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", configuredModel);
        payload.put("messages", buildMessages(request));
        payload.put("temperature", request.getTemperature());
        payload.put("max_tokens", request.getMaxTokens());
        payload.put("stream", false);
        return payload;
    }

    private List<Map<String, Object>> buildMessages(LlmRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(message("system", appendResponseHint(request.getSystemPrompt(), request.getResponseFormatHint())));
        }
        if (request.getMessages() != null) {
            for (LlmMessage item : request.getMessages()) {
                if (item == null || item.getContent() == null || item.getContent().isBlank()) {
                    continue;
                }
                messages.add(message(resolveRole(item.getRole()), item.getContent()));
            }
        }
        if (request.getUserPrompt() != null && !request.getUserPrompt().isBlank()) {
            messages.add(message("user", request.getUserPrompt()));
        }
        return messages;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String resolveRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }
        String normalized = role.trim().toLowerCase();
        return switch (normalized) {
            case "system", "assistant", "user" -> normalized;
            default -> "user";
        };
    }

    private Integer readInteger(JsonNode usage, String fieldName) {
        if (usage == null || usage.isMissingNode() || !usage.has(fieldName) || usage.get(fieldName).isNull()) {
            return null;
        }
        return usage.get(fieldName).asInt();
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

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.deepseek.com";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
