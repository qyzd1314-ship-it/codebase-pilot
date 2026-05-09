package com.yupi.yuaiagent.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private boolean enabled = false;

    private String provider = "dashscope";

    private double defaultTemperature = 0.2D;

    private int defaultMaxTokens = 2048;

    private int retryTimes = 1;

    private int timeoutSeconds = 30;

    private Deepseek deepseek = new Deepseek();

    @Data
    public static class Deepseek {

        private String apiKey = "";

        private String baseUrl = "https://api.deepseek.com";

        private String chatModel = "deepseek-chat";
    }
}
