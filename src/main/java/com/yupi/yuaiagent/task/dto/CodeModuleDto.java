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
public class CodeModuleDto {

    private String name;

    private String responsibility;

    private List<String> evidence;

    private List<String> keyFiles;
}
