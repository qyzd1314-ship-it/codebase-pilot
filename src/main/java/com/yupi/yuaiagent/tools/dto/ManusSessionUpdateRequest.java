package com.yupi.yuaiagent.tools.dto;

import lombok.Data;

import java.util.List;

@Data
public class ManusSessionUpdateRequest {

    private String displayName;

    private List<String> tags;
}
