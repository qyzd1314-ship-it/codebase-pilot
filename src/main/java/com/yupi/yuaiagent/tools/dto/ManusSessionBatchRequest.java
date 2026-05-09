package com.yupi.yuaiagent.tools.dto;

import lombok.Data;

import java.util.List;

@Data
public class ManusSessionBatchRequest {

    private List<String> sessionIds;
}
