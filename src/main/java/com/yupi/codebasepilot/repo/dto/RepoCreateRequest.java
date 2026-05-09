package com.yupi.codebasepilot.repo.dto;

import lombok.Data;

@Data
public class RepoCreateRequest {

    private String url;

    private String branch;
}
