package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.task.service.AgentSafeWorkspaceToolService;
import com.yupi.yuaiagent.task.service.AgentTaskWorkspaceService;
import com.yupi.yuaiagent.agent.ManusSessionMemoryService;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class ManagedToolCallbackFactory {

    private final String searchApiKey;
    private final AgentTaskWorkspaceService agentTaskWorkspaceService;
    private final AgentSafeWorkspaceToolService agentSafeWorkspaceToolService;
    private final ManusSessionApprovalService manusSessionApprovalService;
    private final ManusSessionEventService manusSessionEventService;
    private final ManusSessionToolCallService manusSessionToolCallService;
    private final ManusSessionMemoryService manusSessionMemoryService;

    public ManagedToolCallbackFactory(@Value("${search-api.api-key}") String searchApiKey,
                                      AgentTaskWorkspaceService agentTaskWorkspaceService,
                                      AgentSafeWorkspaceToolService agentSafeWorkspaceToolService,
                                      ManusSessionApprovalService manusSessionApprovalService,
                                      ManusSessionEventService manusSessionEventService,
                                      ManusSessionToolCallService manusSessionToolCallService,
                                      ManusSessionMemoryService manusSessionMemoryService) {
        this.searchApiKey = searchApiKey;
        this.agentTaskWorkspaceService = agentTaskWorkspaceService;
        this.agentSafeWorkspaceToolService = agentSafeWorkspaceToolService;
        this.manusSessionApprovalService = manusSessionApprovalService;
        this.manusSessionEventService = manusSessionEventService;
        this.manusSessionToolCallService = manusSessionToolCallService;
        this.manusSessionMemoryService = manusSessionMemoryService;
    }

    public ToolCallback[] createToolCallbacks(String workspaceKey) {
        File workspaceDir = agentTaskWorkspaceService.createSessionWorkspace(workspaceKey);
        manusSessionMemoryService.updateWorkspace(workspaceKey, workspaceDir.getAbsolutePath());
        manusSessionEventService.recordEvent(
                workspaceKey,
                "WORKSPACE_READY",
                "Session workspace prepared",
                workspaceDir.getAbsolutePath()
        );
        return ToolCallbacks.from(
                new ManagedFileOperationTool(workspaceKey, workspaceDir, agentSafeWorkspaceToolService, manusSessionToolCallService),
                new ManagedWebSearchTool(workspaceKey, searchApiKey, manusSessionToolCallService),
                new ManagedWebScrapingTool(workspaceKey, manusSessionToolCallService),
                new ManagedResourceDownloadTool(
                        workspaceKey, workspaceDir, agentSafeWorkspaceToolService, manusSessionApprovalService, manusSessionEventService, manusSessionToolCallService
                ),
                new ManagedTerminalOperationTool(
                        workspaceKey, workspaceDir, agentSafeWorkspaceToolService, manusSessionApprovalService, manusSessionEventService, manusSessionToolCallService
                ),
                new ManagedPdfGenerationTool(workspaceKey, workspaceDir, agentSafeWorkspaceToolService, manusSessionToolCallService),
                new ManagedTerminateTool(workspaceKey, manusSessionToolCallService)
        );
    }

    static class ManagedFileOperationTool {

        private final String sessionId;
        private final File workspaceDir;
        private final AgentSafeWorkspaceToolService agentSafeWorkspaceToolService;
        private final ManusSessionToolCallService manusSessionToolCallService;

        ManagedFileOperationTool(String sessionId,
                                 File workspaceDir,
                                 AgentSafeWorkspaceToolService agentSafeWorkspaceToolService,
                                 ManusSessionToolCallService manusSessionToolCallService) {
            this.sessionId = sessionId;
            this.workspaceDir = workspaceDir;
            this.agentSafeWorkspaceToolService = agentSafeWorkspaceToolService;
            this.manusSessionToolCallService = manusSessionToolCallService;
        }

        @Tool(description = "Read content from a workspace file")
        public String readFile(@ToolParam(description = "Relative path of the file to read") String fileName) {
            try {
                String result = agentSafeWorkspaceToolService.readFile(workspaceDir, fileName);
                manusSessionToolCallService.recordToolCall(
                        sessionId, "file_read", "FILE", "LOW", fileName, result, true, null
                );
                return result;
            } catch (Exception e) {
                manusSessionToolCallService.recordToolCall(
                        sessionId, "file_read", "FILE", "LOW", fileName, null, false, e.getMessage()
                );
                return "Error reading file: " + e.getMessage();
            }
        }

        @Tool(description = "Write content to a workspace file")
        public String writeFile(@ToolParam(description = "Relative path of the file to write") String fileName,
                                @ToolParam(description = "Content to write to the file") String content) {
            try {
                String result = agentSafeWorkspaceToolService.writeFile(workspaceDir, fileName, content);
                manusSessionToolCallService.recordToolCall(
                        sessionId, "file_write", "FILE", "MEDIUM", fileName, result, true, null
                );
                return result;
            } catch (Exception e) {
                manusSessionToolCallService.recordToolCall(
                        sessionId, "file_write", "FILE", "MEDIUM", fileName, null, false, e.getMessage()
                );
                return "Error writing file: " + e.getMessage();
            }
        }
    }

    static class ManagedWebSearchTool {

        private final String sessionId;
        private final WebSearchTool webSearchTool;
        private final ManusSessionToolCallService manusSessionToolCallService;

        ManagedWebSearchTool(String sessionId, String searchApiKey, ManusSessionToolCallService manusSessionToolCallService) {
            this.sessionId = sessionId;
            this.webSearchTool = new WebSearchTool(searchApiKey);
            this.manusSessionToolCallService = manusSessionToolCallService;
        }

        @Tool(description = "Search for information from Baidu Search Engine")
        public String searchWeb(@ToolParam(description = "Search query keyword") String query) {
            String result = webSearchTool.searchWeb(query);
            boolean success = !result.startsWith("Error ");
            manusSessionToolCallService.recordToolCall(
                    sessionId,
                    "web_search",
                    "WEB",
                    "LOW",
                    query,
                    success ? result : null,
                    success,
                    success ? null : result
            );
            return result;
        }
    }

    static class ManagedWebScrapingTool {

        private final String sessionId;
        private final WebScrapingTool webScrapingTool = new WebScrapingTool();
        private final ManusSessionToolCallService manusSessionToolCallService;

        ManagedWebScrapingTool(String sessionId, ManusSessionToolCallService manusSessionToolCallService) {
            this.sessionId = sessionId;
            this.manusSessionToolCallService = manusSessionToolCallService;
        }

        @Tool(description = "Scrape the content of a web page")
        public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
            String result = webScrapingTool.scrapeWebPage(url);
            boolean success = !result.startsWith("Error ");
            manusSessionToolCallService.recordToolCall(
                    sessionId,
                    "web_scrape",
                    "WEB",
                    "MEDIUM",
                    url,
                    success ? result : null,
                    success,
                    success ? null : result
            );
            return result;
        }
    }

    static class ManagedResourceDownloadTool {

        private final String sessionId;
        private final File workspaceDir;
        private final AgentSafeWorkspaceToolService agentSafeWorkspaceToolService;
        private final ManusSessionApprovalService manusSessionApprovalService;
        private final ManusSessionEventService manusSessionEventService;
        private final ManusSessionToolCallService manusSessionToolCallService;

        ManagedResourceDownloadTool(String sessionId, File workspaceDir,
                                    AgentSafeWorkspaceToolService agentSafeWorkspaceToolService,
                                    ManusSessionApprovalService manusSessionApprovalService,
                                    ManusSessionEventService manusSessionEventService,
                                    ManusSessionToolCallService manusSessionToolCallService) {
            this.sessionId = sessionId;
            this.workspaceDir = workspaceDir;
            this.agentSafeWorkspaceToolService = agentSafeWorkspaceToolService;
            this.manusSessionApprovalService = manusSessionApprovalService;
            this.manusSessionEventService = manusSessionEventService;
            this.manusSessionToolCallService = manusSessionToolCallService;
        }

        @Tool(description = "Download a resource into the current workspace")
        public String downloadResource(@ToolParam(description = "URL of the resource to download") String url,
                                       @ToolParam(description = "Relative path to save the downloaded resource") String fileName) {
            try {
                ManusSessionApprovalService.ApprovalCheckResult checkResult = manusSessionApprovalService.ensureApproved(
                        sessionId, "download_resource", "Downloading remote resources requires approval."
                );
                if (!checkResult.approved()) {
                    if (checkResult.rejected()) {
                        String rejectedMessage = manusSessionApprovalService.buildRejectedMessage(checkResult.approval());
                        manusSessionToolCallService.recordToolCall(
                                sessionId, "download_resource", "WEB", "HIGH", url + " -> " + fileName, null, false, rejectedMessage
                        );
                        return rejectedMessage;
                    }
                    String approvalMessage = manusSessionApprovalService.buildApprovalMessage(checkResult.approval());
                    manusSessionToolCallService.recordToolCall(
                            sessionId, "download_resource", "WEB", "HIGH", url + " -> " + fileName, null, false, approvalMessage
                    );
                    return approvalMessage;
                }
                String result = agentSafeWorkspaceToolService.downloadFile(workspaceDir, fileName, url);
                manusSessionToolCallService.recordToolCall(
                        sessionId, "download_resource", "WEB", "HIGH", url + " -> " + fileName, result, true, null
                );
                manusSessionEventService.recordEvent(
                        sessionId,
                        "TOOL_EXECUTED",
                        "Downloaded resource",
                        url + " -> " + fileName
                );
                return result;
            } catch (Exception e) {
                manusSessionEventService.recordEvent(
                        sessionId,
                        "TOOL_FAILED",
                        "Download failed",
                        e.getMessage()
                );
                manusSessionToolCallService.recordToolCall(
                        sessionId, "download_resource", "WEB", "HIGH", url + " -> " + fileName, null, false, e.getMessage()
                );
                return "Error downloading resource: " + e.getMessage();
            }
        }
    }

    static class ManagedTerminalOperationTool {

        private final String sessionId;
        private final File workspaceDir;
        private final AgentSafeWorkspaceToolService agentSafeWorkspaceToolService;
        private final ManusSessionApprovalService manusSessionApprovalService;
        private final ManusSessionEventService manusSessionEventService;
        private final ManusSessionToolCallService manusSessionToolCallService;

        ManagedTerminalOperationTool(String sessionId, File workspaceDir,
                                     AgentSafeWorkspaceToolService agentSafeWorkspaceToolService,
                                     ManusSessionApprovalService manusSessionApprovalService,
                                     ManusSessionEventService manusSessionEventService,
                                     ManusSessionToolCallService manusSessionToolCallService) {
            this.sessionId = sessionId;
            this.workspaceDir = workspaceDir;
            this.agentSafeWorkspaceToolService = agentSafeWorkspaceToolService;
            this.manusSessionApprovalService = manusSessionApprovalService;
            this.manusSessionEventService = manusSessionEventService;
            this.manusSessionToolCallService = manusSessionToolCallService;
        }

        @Tool(description = "Execute an approved command in the current workspace terminal")
        public String executeTerminalCommand(@ToolParam(description = "Command to execute in the terminal") String command) {
            try {
                ManusSessionApprovalService.ApprovalCheckResult checkResult = manusSessionApprovalService.ensureApproved(
                        sessionId, "terminal_command", "Terminal execution requires approval."
                );
                if (!checkResult.approved()) {
                    if (checkResult.rejected()) {
                        String rejectedMessage = manusSessionApprovalService.buildRejectedMessage(checkResult.approval());
                        manusSessionToolCallService.recordToolCall(
                                sessionId, "terminal_command", "TERMINAL", "HIGH", command, null, false, rejectedMessage
                        );
                        return rejectedMessage;
                    }
                    String approvalMessage = manusSessionApprovalService.buildApprovalMessage(checkResult.approval());
                    manusSessionToolCallService.recordToolCall(
                            sessionId, "terminal_command", "TERMINAL", "HIGH", command, null, false, approvalMessage
                    );
                    return approvalMessage;
                }
                String result = agentSafeWorkspaceToolService.executeTerminalCommand(workspaceDir, command);
                manusSessionToolCallService.recordToolCall(
                        sessionId, "terminal_command", "TERMINAL", "HIGH", command, result, true, null
                );
                manusSessionEventService.recordEvent(
                        sessionId,
                        "TOOL_EXECUTED",
                        "Executed terminal command",
                        command
                );
                return result;
            } catch (Exception e) {
                manusSessionEventService.recordEvent(
                        sessionId,
                        "TOOL_FAILED",
                        "Terminal command failed",
                        e.getMessage()
                );
                manusSessionToolCallService.recordToolCall(
                        sessionId, "terminal_command", "TERMINAL", "HIGH", command, null, false, e.getMessage()
                );
                return "Error executing command: " + e.getMessage();
            }
        }
    }

    static class ManagedPdfGenerationTool {

        private final String sessionId;
        private final File workspaceDir;
        private final AgentSafeWorkspaceToolService agentSafeWorkspaceToolService;
        private final ManusSessionToolCallService manusSessionToolCallService;

        ManagedPdfGenerationTool(String sessionId,
                                 File workspaceDir,
                                 AgentSafeWorkspaceToolService agentSafeWorkspaceToolService,
                                 ManusSessionToolCallService manusSessionToolCallService) {
            this.sessionId = sessionId;
            this.workspaceDir = workspaceDir;
            this.agentSafeWorkspaceToolService = agentSafeWorkspaceToolService;
            this.manusSessionToolCallService = manusSessionToolCallService;
        }

        @Tool(description = "Generate a PDF file in the current workspace", returnDirect = false)
        public String generatePDF(@ToolParam(description = "Relative path of the PDF file to generate") String fileName,
                                  @ToolParam(description = "Content to be included in the PDF") String content) {
            try {
                String result = agentSafeWorkspaceToolService.generatePdf(workspaceDir, fileName, content);
                manusSessionToolCallService.recordToolCall(
                        sessionId, "generate_pdf", "DOCUMENT", "MEDIUM", fileName, result, true, null
                );
                return result;
            } catch (Exception e) {
                manusSessionToolCallService.recordToolCall(
                        sessionId, "generate_pdf", "DOCUMENT", "MEDIUM", fileName, null, false, e.getMessage()
                );
                return "Error generating PDF: " + e.getMessage();
            }
        }
    }

    static class ManagedTerminateTool {

        private final String sessionId;
        private final ManusSessionToolCallService manusSessionToolCallService;

        ManagedTerminateTool(String sessionId, ManusSessionToolCallService manusSessionToolCallService) {
            this.sessionId = sessionId;
            this.manusSessionToolCallService = manusSessionToolCallService;
        }

        @Tool(description = """
                Terminate the interaction when the request is met OR if the assistant cannot proceed further with the task.
                "When you have finished all the tasks, call this tool to end the work.
                """)
        public String doTerminate() {
            String result = "任务结束";
            manusSessionToolCallService.recordToolCall(
                    sessionId, "terminate", "CONTROL", "LOW", "terminate", result, true, null
            );
            return result;
        }
    }
}
