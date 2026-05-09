package com.yupi.codebasepilot.task.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_artifact", indexes = {
        @Index(name = "idx_agent_artifact_task_id", columnList = "task_id"),
        @Index(name = "idx_agent_artifact_artifact_type", columnList = "artifact_type"),
        @Index(name = "idx_agent_artifact_task_type", columnList = "task_id,artifact_type")
})
@Getter
@Setter
public class AgentArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    private Long stepId;

    @Column(nullable = false, length = 64)
    private String artifactType;

    @Column(nullable = false, length = 255)
    private String artifactName;

    @Column(nullable = false, length = 512)
    private String relativePath;

    @Column(length = 128)
    private String contentType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String structuredContent;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(columnDefinition = "TEXT")
    private String evidenceRefs;

    private Long sizeBytes;

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
