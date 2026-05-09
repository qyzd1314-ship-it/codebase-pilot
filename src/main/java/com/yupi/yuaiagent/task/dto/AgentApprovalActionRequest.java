package com.yupi.yuaiagent.task.dto;

import lombok.Data;

@Data
public class AgentApprovalActionRequest {

    private String decisionBy;

    private String decisionNote;
}
