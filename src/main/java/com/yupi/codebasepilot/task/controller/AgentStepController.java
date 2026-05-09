package com.yupi.codebasepilot.task.controller;

import com.yupi.codebasepilot.task.dto.AgentTaskStepDto;
import com.yupi.codebasepilot.task.service.AgentStepService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/agent/tasks")
public class AgentStepController {

    private final AgentStepService agentStepService;

    public AgentStepController(AgentStepService agentStepService) {
        this.agentStepService = agentStepService;
    }

    @GetMapping("/{taskId}/steps")
    public List<AgentTaskStepDto> listSteps(@PathVariable Long taskId) {
        return agentStepService.listSteps(taskId);
    }

    @PostMapping("/{taskId}/steps/{stepId}/retry")
    public AgentTaskStepDto retryStep(@PathVariable Long taskId, @PathVariable Long stepId) {
        return agentStepService.retryStep(taskId, stepId);
    }
}
