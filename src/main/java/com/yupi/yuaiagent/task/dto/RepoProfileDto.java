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
public class RepoProfileDto {

    private String repoId;

    private String projectType;

    private List<String> frameworkHints;

    private List<String> layers;

    private List<RepoCandidateModuleDto> candidateModules;
}
