package com.yupi.codebasepilot.tools.dto;

import lombok.Data;

@Data
public class ManusApprovalActionRequest {

    private String approvedBy;

    private String decisionNote;
}
