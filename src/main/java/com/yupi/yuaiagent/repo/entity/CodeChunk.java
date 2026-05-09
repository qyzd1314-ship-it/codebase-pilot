package com.yupi.yuaiagent.repo.entity;

import com.yupi.yuaiagent.repo.enums.CodeChunkType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "code_chunk", indexes = {
        @Index(name = "idx_code_chunk_repo_id", columnList = "repo_id"),
        @Index(name = "idx_code_chunk_file_path", columnList = "file_path"),
        @Index(name = "idx_code_chunk_repo_symbol", columnList = "repo_id,symbol_name")
})
@Getter
@Setter
public class CodeChunk {

    @Id
    @Column(nullable = false, unique = true, length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String repoId;

    @Column(nullable = false, length = 1024)
    private String filePath;

    @Column(length = 64)
    private String language;

    @Column(length = 255)
    private String symbolName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CodeChunkType chunkType;

    @Column(nullable = false)
    private Integer startLine;

    @Column(nullable = false)
    private Integer endLine;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 128)
    private String contentHash;

    private Integer tokenCount;

    @Column(columnDefinition = "TEXT")
    private String embeddingJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
