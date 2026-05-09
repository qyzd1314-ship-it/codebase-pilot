package com.yupi.yuaiagent.llm;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class NoopLlmEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(context.getEnvironment().getProperty("llm.enabled", Boolean.class, false));
        properties.setProvider(context.getEnvironment().getProperty("llm.provider", "dashscope"));
        properties.setDefaultTemperature(context.getEnvironment().getProperty("llm.default-temperature", Double.class, 0.2D));
        properties.setDefaultMaxTokens(context.getEnvironment().getProperty("llm.default-max-tokens", Integer.class, 2048));
        properties.setRetryTimes(context.getEnvironment().getProperty("llm.retry-times", Integer.class, 1));
        properties.setTimeoutSeconds(context.getEnvironment().getProperty("llm.timeout-seconds", Integer.class, 30));
        String provider = properties.getProvider() == null ? "" : properties.getProvider().trim().toLowerCase();
        if ("deepseek".equals(provider)) {
            String apiKey = context.getEnvironment().getProperty("llm.deepseek.api-key");
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = context.getEnvironment().getProperty("DEEPSEEK_API_KEY", "");
            }
            return !LlmAvailability.isDeepSeekAvailable(properties, apiKey);
        }
        String apiKey = context.getEnvironment().getProperty("spring.ai.dashscope.api-key");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = context.getEnvironment().getProperty("DASHSCOPE_API_KEY", "");
        }
        return !LlmAvailability.isDashScopeAvailable(properties, apiKey);
    }
}
