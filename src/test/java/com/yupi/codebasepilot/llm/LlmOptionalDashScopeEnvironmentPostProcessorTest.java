package com.yupi.codebasepilot.llm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class LlmOptionalDashScopeEnvironmentPostProcessorTest {

    @Test
    void shouldExcludeDashScopeAutoConfigWhenDisabled() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("llm.enabled", "false")
                .withProperty("llm.provider", "dashscope")
                .withProperty("spring.ai.dashscope.api-key", "");

        new LlmOptionalDashScopeEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        String excludes = environment.getProperty("spring.autoconfigure.exclude", "");
        Assertions.assertTrue(excludes.contains("DashScopeChatAutoConfiguration"));
        Assertions.assertTrue(excludes.contains("DashScopeEmbeddingAutoConfiguration"));
    }

    @Test
    void shouldKeepDashScopeAutoConfigWhenEnabledAndConfigured() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("llm.enabled", "true")
                .withProperty("llm.provider", "dashscope")
                .withProperty("spring.ai.dashscope.api-key", "test-key");

        new LlmOptionalDashScopeEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        String excludes = environment.getProperty("spring.autoconfigure.exclude");
        Assertions.assertTrue(excludes == null || !excludes.contains("DashScopeChatAutoConfiguration"));
    }
}
