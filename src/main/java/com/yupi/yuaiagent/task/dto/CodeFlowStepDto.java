package com.yupi.yuaiagent.task.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeFlowStepDto {

    private String step;

    private String description;

    private List<String> keyFiles;

    private List<String> evidence;
}
