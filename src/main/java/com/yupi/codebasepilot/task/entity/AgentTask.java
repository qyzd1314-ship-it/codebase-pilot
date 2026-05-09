package com.yupi.codebasepilot.task.entity;

import com.yupi.codebasepilot.task.enums.AgentTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "agent_task", indexes = {
        @Index(name = "idx_agent_task_repo_id", columnList = "repo_id"),
        @Index(name = "idx_agent_task_status", columnList = "status"),
        @Index(name = "idx_agent_task_repo_status", columnList = "repo_id,status")
})
@Getter
@Setter
public class AgentTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String taskNo;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String goal;

    @Column(nullable = false, length = 64)
    private String taskType;

    @Column(length = 64)
    private String repoId;

    @Column(length = 64)
    private String businessType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentTaskStatus status;

    @Column(length = 128)
    private String conversationId;

    @Column(length = 512)
    private String workspacePath;

    private Long sourceTaskId;

    @Column(length = 64)
    private String sourceTaskNo;

    @Column(length = 255)
    private String sourceTaskTitle;

    @Column(length = 32)
    private String sourceTaskRelation;

    @Column(columnDefinition = "TEXT")
    private String inheritedContextSummary;

    private Integer currentStepSeq;

    @Column(columnDefinition = "TEXT")
    private String planSummary;

    @Column(columnDefinition = "TEXT")
    private String previousPlanSummary;

    @Column(columnDefinition = "TEXT")
    private String previousPlanStepsSnapshot;

    @Column(columnDefinition = "TEXT")
    private String planDiffSummary;

    @Column(columnDefinition = "TEXT")
    private String planDiffSnapshot;

    @Column(columnDefinition = "TEXT")
    private String finalResult;

    @Column(columnDefinition = "TEXT")
    private String handoffContextSummary;

    @Column(columnDefinition = "TEXT")
    private String taskSummary;

    @Column(length = 64)
    private String deliveryStatus;

    private Integer artifactCount;

    private Integer deliverableArtifactCount;

    @Column(columnDefinition = "TEXT")
    private String reviewSummary;

    @Column(length = 64)
    private String reviewSuggestedAction;

    private Integer reviewSuggestedStepSeq;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private Boolean autoApproveLowRisk;

    @Column(nullable = false)
    private Integer currentRound;

    @Column(nullable = false)
    private Integer maxRound;

    @Column(nullable = false)
    private Integer replanCount;

    @Column(nullable = false)
    private Integer consecutiveSameReasonReplanCount;

    @Column(nullable = false)
    private Integer maxConsecutiveSameReasonReplanCount;

    @Column(columnDefinition = "TEXT")
    private String lastReplanReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.autoApproveLowRisk == null) {
            this.autoApproveLowRisk = false;
        }
        if (this.currentRound == null) {
            this.currentRound = 0;
        }
        if (this.maxRound == null) {
            this.maxRound = 8;
        }
        if (this.replanCount == null) {
            this.replanCount = 0;
        }
        if (this.consecutiveSameReasonReplanCount == null) {
            this.consecutiveSameReasonReplanCount = 0;
        }
        if (this.maxConsecutiveSameReasonReplanCount == null) {
            this.maxConsecutiveSameReasonReplanCount = 3;
        }
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
