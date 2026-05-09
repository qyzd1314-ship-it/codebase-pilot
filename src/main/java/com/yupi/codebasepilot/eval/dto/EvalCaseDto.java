package com.yupi.codebasepilot.eval.dto;

import lombok.Data;

import java.util.List;

@Data
public class EvalCaseDto {

    private String id;

    private String repoId;

    private String question;

    private List<String> expectedFiles;

    private List<String> expectedKeywords;

    private String expectedRootCause;

    private String caseType;

    private String difficulty;
}
