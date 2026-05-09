package com.yupi.codebasepilot.repo.entity;

import com.yupi.codebasepilot.repo.enums.RepoIndexedStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "repo", indexes = {
        @Index(name = "idx_repo_indexed_status", columnList = "indexed_status"),
        @Index(name = "idx_repo_last_indexed_at", columnList = "last_indexed_at")
})
@Getter
@Setter
public class Repo {

    @Id
    @Column(nullable = false, unique = true, length = 64)
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 1024)
    private String url;

    @Column(nullable = false, length = 128)
    private String branch;

    @Column(nullable = false, length = 1024)
    private String localPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RepoIndexedStatus indexedStatus;

    @Column(nullable = false)
    private Integer fileCount;

    @Column(nullable = false)
    private Integer chunkCount;

    private LocalDateTime lastIndexedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
