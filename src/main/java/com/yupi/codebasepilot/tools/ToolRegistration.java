package com.yupi.codebasepilot.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 闆嗕腑鐨勫伐鍏锋敞鍐岀�? */
@Configuration
public class ToolRegistration {

    private final ManagedToolCallbackFactory managedToolCallbackFactory;

    public ToolRegistration(ManagedToolCallbackFactory managedToolCallbackFactory) {
        this.managedToolCallbackFactory = managedToolCallbackFactory;
    }

    @Bean
    public ToolCallback[] allTools() {
        return managedToolCallbackFactory.createToolCallbacks("default-shared");
    }
}
