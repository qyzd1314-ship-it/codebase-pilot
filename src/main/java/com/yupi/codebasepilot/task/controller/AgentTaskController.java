package com.yupi.codebasepilot.task.controller;

import com.yupi.codebasepilot.task.dto.AgentTaskCreateRequest;
import com.yupi.codebasepilot.task.dto.AgentTaskOverviewResponse;
import com.yupi.codebasepilot.task.dto.AgentTaskResponse;
import com.yupi.codebasepilot.task.service.AgentTaskEventService;
import com.yupi.codebasepilot.task.service.AgentTaskService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agent/tasks")
public class AgentTaskController {

    private final AgentTaskService agentTaskService;
    private final AgentTaskEventService agentTaskEventService;

    public AgentTaskController(AgentTaskService agentTaskService, AgentTaskEventService agentTaskEventService) {
        this.agentTaskService = agentTaskService;
        this.agentTaskEventService = agentTaskEventService;
    }

    @PostMapping
    public AgentTaskResponse createTask(@RequestBody AgentTaskCreateRequest request) {
        return agentTaskService.createTask(request);
    }

    @GetMapping
    public List<AgentTaskResponse> listTasks(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String deliveryStatus,
                                             @RequestParam(required = false) String sortBy) {
        return agentTaskService.listTasks(status, deliveryStatus, sortBy);
    }

    @GetMapping("/overview")
    public AgentTaskOverviewResponse getTaskOverview(@RequestParam(defaultValue = "4") int limit) {
        return agentTaskService.getTaskOverview(limit);
    }

    @GetMapping("/{taskId}")
    public AgentTaskResponse getTask(@PathVariable Long taskId) {
        return agentTaskService.getTask(taskId);
    }

    @PostMapping("/{taskId}/start")
    public AgentTaskResponse startTask(@PathVariable Long taskId) {
        return agentTaskService.startTask(taskId);
    }

    @PostMapping("/{taskId}/duplicate")
    public AgentTaskResponse duplicateTask(@PathVariable Long taskId) {
        return agentTaskService.duplicateTask(taskId);
    }

    @PostMapping("/{taskId}/follow-up")
    public AgentTaskResponse createFollowUpTask(@PathVariable Long taskId) {
        return agentTaskService.createFollowUpTask(taskId);
    }

    @PostMapping("/{taskId}/follow-up-and-start")
    public AgentTaskResponse createFollowUpTaskAndStart(@PathVariable Long taskId) {
        return agentTaskService.createFollowUpTaskAndStart(taskId);
    }

    @PostMapping("/{taskId}/pause")
    public AgentTaskResponse pauseTask(@PathVariable Long taskId) {
        return agentTaskService.pauseTask(taskId);
    }

    @PostMapping("/{taskId}/resume")
    public AgentTaskResponse resumeTask(@PathVariable Long taskId) {
        return agentTaskService.resumeTask(taskId);
    }

    @PostMapping("/{taskId}/review")
    public AgentTaskResponse reviewTask(@PathVariable Long taskId) {
        return agentTaskService.reviewTask(taskId);
    }

    @PostMapping("/{taskId}/replan")
    public AgentTaskResponse replanTask(@PathVariable Long taskId) {
        return agentTaskService.replanTask(taskId);
    }

    @PostMapping("/{taskId}/confirm-plan")
    public AgentTaskResponse confirmPlan(@PathVariable Long taskId) {
        return agentTaskService.confirmPlan(taskId);
    }

    @PostMapping("/{taskId}/cancel")
    public AgentTaskResponse cancelTask(@PathVariable Long taskId) {
        return agentTaskService.cancelTask(taskId);
    }

    @GetMapping("/{taskId}/events/stream")
    public SseEmitter streamTaskEvents(@PathVariable Long taskId) {
        return agentTaskEventService.createEmitter(taskId);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(EntityNotFoundException e) {
        return Map.of("message", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleIllegalState(IllegalStateException e) {
        return Map.of("message", e.getMessage());
    }
}
