package com.yupi.codebasepilot.task.entity;

import com.yupi.codebasepilot.task.enums.AgentTaskStepStatus;
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
@Table(name = "agent_task_step", indexes = {
        @Index(name = "idx_agent_task_step_task_id", columnList = "task_id"),
        @Index(name = "idx_agent_task_step_task_seq", columnList = "task_id,step_seq"),
        @Index(name = "idx_agent_task_step_status", columnList = "status")
})
@Getter
@Setter
public class AgentTaskStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private Integer stepSeq;

    @Column(nullable = false, length = 255)
    private String stepTitle;

    @Column(nullable = false, length = 64)
    private String stepType;

    @Column(nullable = false, length = 64)
    private String assignedAgent;

    @Column(nullable = false, length = 128)
    private String toolName;

    @Column(nullable = false, length = 64)
    private String toolCategory;

    @Column(nullable = false, length = 32)
    private String riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentTaskStepStatus status;

    @Column(columnDefinition = "TEXT")
    private String plannerOutput;

    @Column(columnDefinition = "TEXT")
    private String executorInput;

    @Column(columnDefinition = "TEXT")
    private String executorOutput;

    @Column(columnDefinition = "TEXT")
    private String evidenceRefs;

    @Column(nullable = false)
    private Integer retryCount;

    @Column(nullable = false)
    private Integer maxRetry;

    @Column(nullable = false)
    private Boolean requiresApproval;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.assignedAgent == null) {
            this.assignedAgent = "LegacyWorker";
        }
        if (this.retryCount == null) {
            this.retryCount = 0;
        }
        if (this.maxRetry == null) {
            this.maxRetry = 1;
        }
        if (this.requiresApproval == null) {
            this.requiresApproval = false;
        }
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
