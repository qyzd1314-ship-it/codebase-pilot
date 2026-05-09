package com.yupi.codebasepilot.tools.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ManusSessionMessageDto {

    private String role;

    private String content;

    private String messageType;

    private Long createdAt;
}
