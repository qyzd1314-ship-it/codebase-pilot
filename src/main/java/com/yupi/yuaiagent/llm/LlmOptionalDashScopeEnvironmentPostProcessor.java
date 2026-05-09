package com.yupi.yuaiagent.llm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class LlmOptionalDashScopeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "llmOptionalDashScopeExcludes";

    private static final Set<String> DASHSCOPE_AUTO_CONFIGS = Set.of(
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeImageAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeRerankAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionAutoConfiguration"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(environment.getProperty("llm.enabled", Boolean.class, false));
        properties.setProvider(environment.getProperty("llm.provider", "dashscope"));
        String apiKey = resolveDashScopeApiKey(environment);
        if (LlmAvailability.isDashScopeAvailable(properties, apiKey)) {
            return;
        }

        String existing = environment.getProperty("spring.autoconfigure.exclude", "");
        Set<String> excludes = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            for (String item : existing.split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isBlank()) {
                    excludes.add(trimmed);
                }
            }
        }
        excludes.addAll(DASHSCOPE_AUTO_CONFIGS);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spring.autoconfigure.exclude", String.join(",", excludes));
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String resolveDashScopeApiKey(ConfigurableEnvironment environment) {
        String apiKey = environment.getProperty("spring.ai.dashscope.api-key");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = environment.getProperty("DASHSCOPE_API_KEY", "");
        }
        return apiKey == null ? "" : apiKey.trim();
    }
}
