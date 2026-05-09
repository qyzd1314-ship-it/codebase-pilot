package com.yupi.codebasepilot.controller;

import com.yupi.codebasepilot.agent.YuManus;
import com.yupi.codebasepilot.agent.ManusSessionMemoryService;
import com.yupi.codebasepilot.app.LoveApp;
import com.yupi.codebasepilot.tools.ManagedToolCallbackFactory;
import com.yupi.codebasepilot.tools.ManusSessionApprovalService;
import com.yupi.codebasepilot.tools.ManusSessionEventService;
import com.yupi.codebasepilot.tools.ManusSessionManagementService;
import com.yupi.codebasepilot.tools.ManusSessionQueryService;
import com.yupi.codebasepilot.tools.ManusSessionToolCallService;
import com.yupi.codebasepilot.tools.dto.ManusApprovalActionRequest;
import com.yupi.codebasepilot.tools.dto.ManusSessionEventDto;
import com.yupi.codebasepilot.tools.dto.ManusSessionDto;
import com.yupi.codebasepilot.tools.dto.ManusSessionBatchRequest;
import com.yupi.codebasepilot.tools.dto.ManusSessionMessageDto;
import com.yupi.codebasepilot.tools.dto.ManusSessionUpdateRequest;
import com.yupi.codebasepilot.tools.dto.ManusToolCallDto;
import com.yupi.codebasepilot.tools.dto.ManusToolApprovalDto;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/ai")
@ConditionalOnBean(name = "dashscopeChatModel")
public class AiController {

    @Resource
    private LoveApp loveApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ManagedToolCallbackFactory managedToolCallbackFactory;

    @Resource
    private ManusSessionApprovalService manusSessionApprovalService;

    @Resource
    private ManusSessionMemoryService manusSessionMemoryService;

    @Resource
    private ManusSessionEventService manusSessionEventService;

    @Resource
    private ManusSessionToolCallService manusSessionToolCallService;

    @Resource
    private ManusSessionQueryService manusSessionQueryService;

    @Resource
    private ManusSessionManagementService manusSessionManagementService;

    @Resource
    private ChatModel dashscopeChatModel;

    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        return loveApp.doChat(message, chatId);
    }

    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId);
    }

    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    @GetMapping(value = "/love_app/chat/sse_emitter")
    public SseEmitter doChatWithLoveAppServerSseEmitter(String message, String chatId) {
        SseEmitter sseEmitter = new SseEmitter(180000L);
        loveApp.doChatByStream(message, chatId)
                .subscribe(chunk -> {
                    try {
                        sseEmitter.send(chunk);
                    } catch (IOException e) {
                        sseEmitter.completeWithError(e);
                    }
                }, sseEmitter::completeWithError, sseEmitter::complete);
        return sseEmitter;
    }

    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message, String sessionId) {
        String effectiveSessionId = (sessionId == null || sessionId.isBlank()) ? "manus-" + UUID.randomUUID() : sessionId;
        manusSessionMemoryService.ensureSession(effectiveSessionId);
        manusSessionEventService.recordEvent(
                effectiveSessionId,
                "USER_MESSAGE",
                "User request",
                message
        );
        ToolCallback[] scopedTools = managedToolCallbackFactory.createToolCallbacks(effectiveSessionId);
        YuManus yuManus = new YuManus(scopedTools, dashscopeChatModel);
        yuManus.setMessageList(manusSessionMemoryService.loadMessages(effectiveSessionId));
        manusSessionEventService.recordEvent(
                effectiveSessionId,
                "SESSION_RUNNING",
                "Agent execution started",
                "Processing message in session " + effectiveSessionId
        );
        SseEmitter emitter = yuManus.runStream(message);
        emitter.onCompletion(() -> {
            manusSessionMemoryService.saveMessages(effectiveSessionId, yuManus.getMessageList());
            manusSessionEventService.recordEvent(
                    effectiveSessionId,
                    "SESSION_COMPLETED",
                    "Agent execution completed",
                    "The current streaming round has finished."
            );
        });
        emitter.onTimeout(() -> {
            manusSessionMemoryService.saveMessages(effectiveSessionId, yuManus.getMessageList());
            manusSessionEventService.recordEvent(
                    effectiveSessionId,
                    "SESSION_TIMEOUT",
                    "Agent execution timed out",
                    "The streaming round timed out before finishing."
            );
        });
        emitter.onError(error -> {
            manusSessionMemoryService.saveMessages(effectiveSessionId, yuManus.getMessageList());
            manusSessionEventService.recordEvent(
                    effectiveSessionId,
                    "SESSION_ERROR",
                    "Agent execution failed",
                    error == null ? "Unknown session error." : error.getMessage()
            );
        });
        return emitter;
    }

    @GetMapping("/manus/sessions")
    public List<ManusSessionDto> listManusSessions(String keyword, String status, String sortBy, String tag) {
        return manusSessionQueryService.listSessions(keyword, status, sortBy, tag);
    }

    @GetMapping("/manus/sessions/{sessionId}")
    public ManusSessionDto getManusSession(@PathVariable String sessionId) {
        return manusSessionQueryService.getSession(sessionId);
    }

    @GetMapping("/manus/sessions/{sessionId}/messages")
    public List<ManusSessionMessageDto> listManusSessionMessages(@PathVariable String sessionId) {
        return manusSessionMemoryService.listMessageSnapshots(sessionId);
    }

    @PostMapping("/manus/sessions/{sessionId}/rename")
    public ManusSessionDto renameManusSession(@PathVariable String sessionId,
                                              @RequestBody(required = false) ManusSessionUpdateRequest request) {
        ManusSessionDto session = manusSessionManagementService.renameSession(
                sessionId,
                request == null ? sessionId : request.getDisplayName()
        );
        if (request != null && request.getTags() != null) {
            return manusSessionManagementService.updateTags(sessionId, request.getTags());
        }
        return session;
    }

    @PostMapping("/manus/sessions/{sessionId}/tags")
    public ManusSessionDto updateManusSessionTags(@PathVariable String sessionId,
                                                  @RequestBody(required = false) ManusSessionUpdateRequest request) {
        return manusSessionManagementService.updateTags(
                sessionId,
                request == null ? List.of() : request.getTags()
        );
    }

    @PostMapping("/manus/sessions/{sessionId}/archive")
    public ManusSessionDto archiveManusSession(@PathVariable String sessionId) {
        return manusSessionManagementService.archiveSession(sessionId);
    }

    @PostMapping("/manus/sessions/{sessionId}/activate")
    public ManusSessionDto activateManusSession(@PathVariable String sessionId) {
        return manusSessionManagementService.activateSession(sessionId);
    }

    @PostMapping("/manus/sessions/{sessionId}/clear")
    public ManusSessionDto clearManusSession(@PathVariable String sessionId) {
        return manusSessionManagementService.clearSession(sessionId);
    }

    @PostMapping("/manus/sessions/{sessionId}/pin")
    public ManusSessionDto pinManusSession(@PathVariable String sessionId) {
        return manusSessionManagementService.pinSession(sessionId);
    }

    @PostMapping("/manus/sessions/{sessionId}/unpin")
    public ManusSessionDto unpinManusSession(@PathVariable String sessionId) {
        return manusSessionManagementService.unpinSession(sessionId);
    }

    @PostMapping("/manus/sessions/batch/archive")
    public List<ManusSessionDto> batchArchiveManusSessions(@RequestBody ManusSessionBatchRequest request) {
        return manusSessionManagementService.batchArchive(request.getSessionIds());
    }

    @PostMapping("/manus/sessions/batch/activate")
    public List<ManusSessionDto> batchActivateManusSessions(@RequestBody ManusSessionBatchRequest request) {
        return manusSessionManagementService.batchActivate(request.getSessionIds());
    }

    @PostMapping("/manus/sessions/batch/pin")
    public List<ManusSessionDto> batchPinManusSessions(@RequestBody ManusSessionBatchRequest request) {
        return manusSessionManagementService.batchPin(request.getSessionIds());
    }

    @PostMapping("/manus/sessions/batch/unpin")
    public List<ManusSessionDto> batchUnpinManusSessions(@RequestBody ManusSessionBatchRequest request) {
        return manusSessionManagementService.batchUnpin(request.getSessionIds());
    }

    @GetMapping("/manus/events")
    public List<ManusSessionEventDto> listManusEvents(String sessionId) {
        return manusSessionEventService.listEvents(sessionId).stream()
                .map(event -> ManusSessionEventDto.builder()
                        .id(event.id())
                        .sessionId(event.sessionId())
                        .eventType(event.eventType())
                        .title(event.title())
                        .content(event.content())
                        .createdAt(event.createdAt())
                        .build())
                .toList();
    }

    @GetMapping("/manus/tool-calls")
    public List<ManusToolCallDto> listManusToolCalls(String sessionId) {
        return manusSessionToolCallService.listToolCalls(sessionId).stream()
                .map(toolCall -> ManusToolCallDto.builder()
                        .id(toolCall.id())
                        .sessionId(toolCall.sessionId())
                        .toolName(toolCall.toolName())
                        .toolCategory(toolCall.toolCategory())
                        .riskLevel(toolCall.riskLevel())
                        .requestPayload(toolCall.requestPayload())
                        .responsePayload(toolCall.responsePayload())
                        .success(toolCall.success())
                        .errorMessage(toolCall.errorMessage())
                        .startedAt(toolCall.startedAt())
                        .finishedAt(toolCall.finishedAt())
                        .build())
                .toList();
    }

    @GetMapping("/manus/approvals")
    public List<ManusToolApprovalDto> listManusApprovals(String sessionId) {
        return manusSessionApprovalService.listApprovals(sessionId).stream()
                .map(item -> ManusToolApprovalDto.builder()
                        .sessionId(item.sessionId())
                        .toolName(item.toolName())
                        .status(item.status().name())
                        .reason(item.reason())
                        .approvedBy(item.approvedBy())
                        .decisionNote(item.decisionNote())
                        .createdAt(item.createdAt())
                        .decidedAt(item.decidedAt())
                        .build())
                .toList();
    }

    @PostMapping("/manus/approvals/{sessionId}/{toolName}/approve")
    public ManusToolApprovalDto approveManusTool(@PathVariable String sessionId,
                                                 @PathVariable String toolName,
                                                 @RequestBody(required = false) ManusApprovalActionRequest request) {
        ManusSessionApprovalService.ManusToolApproval approval = manusSessionApprovalService.approve(
                sessionId,
                toolName,
                request == null ? "web-user" : request.getApprovedBy(),
                request == null ? "Approved from web ui." : request.getDecisionNote()
        );
        return toApprovalDto(approval);
    }

    @PostMapping("/manus/approvals/{sessionId}/{toolName}/reject")
    public ManusToolApprovalDto rejectManusTool(@PathVariable String sessionId,
                                                @PathVariable String toolName,
                                                @RequestBody(required = false) ManusApprovalActionRequest request) {
        ManusSessionApprovalService.ManusToolApproval approval = manusSessionApprovalService.reject(
                sessionId,
                toolName,
                request == null ? "web-user" : request.getApprovedBy(),
                request == null ? "Rejected from web ui." : request.getDecisionNote()
        );
        return toApprovalDto(approval);
    }

    private ManusToolApprovalDto toApprovalDto(ManusSessionApprovalService.ManusToolApproval approval) {
        return ManusToolApprovalDto.builder()
                .sessionId(approval.sessionId())
                .toolName(approval.toolName())
                .status(approval.status().name())
                .reason(approval.reason())
                .approvedBy(approval.approvedBy())
                .decisionNote(approval.decisionNote())
                .createdAt(approval.createdAt())
                .decidedAt(approval.decidedAt())
                .build();
    }
}
