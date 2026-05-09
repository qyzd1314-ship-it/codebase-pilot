package com.yupi.yuaiagent.repo.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class RepoResponse {

    String repoId;
    String name;
    String url;
    String branch;
    String localPath;
    String indexedStatus;
    Integer fileCount;
    Integer chunkCount;
    LocalDateTime lastIndexedAt;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
