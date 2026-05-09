package com.yupi.codebasepilot.task.dto;

import lombok.Data;

@Data
public class AgentTaskCreateRequest {

    private String title;

    private String goal;

    private String taskType;

    private String repoId;

    private String businessType;

    private Boolean autoApproveLowRisk;
}
