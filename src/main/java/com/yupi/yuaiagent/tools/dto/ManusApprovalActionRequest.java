package com.yupi.yuaiagent.tools.dto;

import lombok.Data;

@Data
public class ManusApprovalActionRequest {

    private String approvedBy;

    private String decisionNote;
}
