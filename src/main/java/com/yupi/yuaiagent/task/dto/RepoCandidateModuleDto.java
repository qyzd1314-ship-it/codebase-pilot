package com.yupi.yuaiagent.task.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoCandidateModuleDto {

    private String name;

    private String displayName;

    private List<String> keywords;

    private List<String> files;

    private Map<String, List<String>> layerFiles;

    private Integer weight;
}
