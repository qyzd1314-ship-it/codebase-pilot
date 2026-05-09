package com.yupi.codebasepilot.llm;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DashScopeLlmEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        LlmProperties properties = bindProperties(context);
        String apiKey = resolveDashScopeApiKey(context);
        return LlmAvailability.isDashScopeAvailable(properties, apiKey);
    }

    private LlmProperties bindProperties(ConditionContext context) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(context.getEnvironment().getProperty("llm.enabled", Boolean.class, false));
        properties.setProvider(context.getEnvironment().getProperty("llm.provider", "dashscope"));
        properties.setDefaultTemperature(context.getEnvironment().getProperty("llm.default-temperature", Double.class, 0.2D));
        properties.setDefaultMaxTokens(context.getEnvironment().getProperty("llm.default-max-tokens", Integer.class, 2048));
        properties.setRetryTimes(context.getEnvironment().getProperty("llm.retry-times", Integer.class, 1));
        properties.setTimeoutSeconds(context.getEnvironment().getProperty("llm.timeout-seconds", Integer.class, 30));
        return properties;
    }

    private String resolveDashScopeApiKey(ConditionContext context) {
        String apiKey = context.getEnvironment().getProperty("spring.ai.dashscope.api-key");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = context.getEnvironment().getProperty("DASHSCOPE_API_KEY", "");
        }
        return apiKey == null ? "" : apiKey.trim();
    }
}
