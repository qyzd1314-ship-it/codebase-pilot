package com.yupi.codebasepilot.task.service;

import com.yupi.codebasepilot.task.entity.AgentTask;
import com.yupi.codebasepilot.task.entity.AgentTaskStep;
import org.springframework.stereotype.Service;

@Service
public class AgentWorkerService {

    private final AgentToolExecutionGateway agentToolExecutionGateway;

    public AgentWorkerService(AgentToolExecutionGateway agentToolExecutionGateway) {
        this.agentToolExecutionGateway = agentToolExecutionGateway;
    }

    public WorkerResult executeStep(AgentTask task, AgentTaskStep step, String toolName, String toolCategory, String riskLevel) {
        AgentToolExecutionGateway.ToolExecutionResult executionResult = agentToolExecutionGateway.execute(task, step);
        if (!executionResult.success()) {
            return new WorkerResult(
                    "Worker failed while executing tool " + toolName + ".",
                    false,
                    executionResult.errorMessage()
            );
        }
        String output = switch (toolName) {
            case "task_context_reader" -> "Worker confirmed task scope and loaded inherited context when available: "
                    + executionResult.output();
            case "terminal_command" -> "Worker executed a controlled terminal command in the workspace." + System.lineSeparator() + executionResult.output();
            case "file_writer" -> "Worker created a workspace draft artifact." + System.lineSeparator() + executionResult.output();
            case "resource_downloader" -> "Worker downloaded a resource into the workspace." + System.lineSeparator() + executionResult.output();
            case "final_response_writer" -> "Worker packaged the final deliverable with inherited context and incremental delivery notes when relevant."
                    + System.lineSeparator() + executionResult.output();
            default -> executionResult.output();
        };
        return new WorkerResult(output, true, null);
    }

    public record WorkerResult(String output, boolean success, String errorMessage) {
    }
}
