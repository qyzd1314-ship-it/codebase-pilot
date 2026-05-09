package com.yupi.yuaiagent.task.controller;

import com.yupi.yuaiagent.task.dto.AgentToolCallDto;
import com.yupi.yuaiagent.task.service.AgentToolCallService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/agent/tasks")
public class AgentToolCallController {

    private final AgentToolCallService agentToolCallService;

    public AgentToolCallController(AgentToolCallService agentToolCallService) {
        this.agentToolCallService = agentToolCallService;
    }

    @GetMapping("/{taskId}/tool-calls")
    public List<AgentToolCallDto> listToolCalls(@PathVariable Long taskId) {
        return agentToolCallService.listToolCalls(taskId);
    }
}
