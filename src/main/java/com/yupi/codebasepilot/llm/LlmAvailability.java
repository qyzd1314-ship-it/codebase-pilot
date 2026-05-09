package com.yupi.codebasepilot.llm;

public final class LlmAvailability {

    private LlmAvailability() {
    }

    public static boolean isDashScopeAvailable(LlmProperties properties, String apiKey) {
        return properties != null
                && properties.isEnabled()
                && "dashscope".equalsIgnoreCase(defaultString(properties.getProvider()))
                && apiKey != null
                && !apiKey.isBlank();
    }

    public static boolean isDeepSeekAvailable(LlmProperties properties, String apiKey) {
        return properties != null
                && properties.isEnabled()
                && "deepseek".equalsIgnoreCase(defaultString(properties.getProvider()))
                && apiKey != null
                && !apiKey.isBlank();
    }

    public static String unavailableReason(LlmProperties properties, String apiKey) {
        if (properties == null || !properties.isEnabled()) {
            return "LLM is disabled or the provider API key is not configured";
        }
        String provider = defaultString(properties.getProvider()).toLowerCase();
        if (!"dashscope".equals(provider) && !"deepseek".equals(provider)) {
            return "LLM provider is not supported or not configured";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return switch (provider) {
                case "deepseek" -> "LLM is disabled or DEEPSEEK_API_KEY is not configured";
                case "dashscope" -> "LLM is disabled or DASHSCOPE_API_KEY is not configured";
                default -> "LLM is disabled or the provider API key is not configured";
            };
        }
        if ("deepseek".equals(provider)) {
            return "DeepSeek LLM is unavailable";
        }
        return "DashScope LLM is unavailable";
    }

    private static String defaultString(String value) {
        return value == null ? "" : value.trim();
    }
}
