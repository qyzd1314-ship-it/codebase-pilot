package com.yupi.codebasepilot.task.service;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AgentToolPolicyService {

    private final Map<String, ToolPolicy> toolPolicies = Map.of(
            "task_context_reader", new ToolPolicy(
                    "task_context_reader",
                    "CONTEXT",
                    "LOW",
                    false,
                    "Read current task context and summarize the user goal."
            ),
            "terminal_command", new ToolPolicy(
                    "terminal_command",
                    "SYSTEM",
                    "HIGH",
                    true,
                    "Run a terminal command inside the task workspace."
            ),
            "file_writer", new ToolPolicy(
                    "file_writer",
                    "FILESYSTEM",
                    "MEDIUM",
                    true,
                    "Write generated content into a workspace file."
            ),
            "resource_downloader", new ToolPolicy(
                    "resource_downloader",
                    "NETWORK",
                    "HIGH",
                    true,
                    "Download a remote resource into the workspace."
            ),
            "final_response_writer", new ToolPolicy(
                    "final_response_writer",
                    "DELIVERY",
                    "LOW",
                    false,
                    "Package the current task result for delivery."
            )
    );

    public ToolPolicy getPolicy(String toolName) {
        ToolPolicy toolPolicy = toolPolicies.get(toolName);
        if (toolPolicy == null) {
            throw new IllegalArgumentException("Unsupported tool policy: " + toolName);
        }
        return toolPolicy;
    }

    public record ToolPolicy(
            String toolName,
            String category,
            String riskLevel,
            boolean approvalRequired,
            String summary
    ) {
    }
}
