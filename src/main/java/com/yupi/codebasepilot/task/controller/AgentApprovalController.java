package com.yupi.codebasepilot.task.controller;

import com.yupi.codebasepilot.task.dto.AgentApprovalActionRequest;
import com.yupi.codebasepilot.task.dto.AgentApprovalDto;
import com.yupi.codebasepilot.task.service.AgentApprovalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/agent")
public class AgentApprovalController {

    private final AgentApprovalService agentApprovalService;

    public AgentApprovalController(AgentApprovalService agentApprovalService) {
        this.agentApprovalService = agentApprovalService;
    }

    @GetMapping("/tasks/{taskId}/approvals")
    public List<AgentApprovalDto> listApprovals(@PathVariable Long taskId) {
        return agentApprovalService.listApprovals(taskId);
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public AgentApprovalDto approve(@PathVariable Long approvalId,
                                    @RequestBody(required = false) AgentApprovalActionRequest request) {
        return agentApprovalService.approve(approvalId, request);
    }

    @PostMapping("/approvals/{approvalId}/reject")
    public AgentApprovalDto reject(@PathVariable Long approvalId,
                                   @RequestBody(required = false) AgentApprovalActionRequest request) {
        return agentApprovalService.reject(approvalId, request);
    }
}
