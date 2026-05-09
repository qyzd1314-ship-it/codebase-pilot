package com.yupi.codebasepilot.task.entity;

import com.yupi.codebasepilot.task.enums.AgentApprovalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_approval", indexes = {
        @Index(name = "idx_agent_approval_task_id", columnList = "task_id"),
        @Index(name = "idx_agent_approval_step_id", columnList = "step_id"),
        @Index(name = "idx_agent_approval_status", columnList = "status")
})
@Getter
@Setter
public class AgentApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    private Long stepId;

    @Column(nullable = false, length = 64)
    private String approvalType;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentApprovalStatus status;

    @Column(length = 64)
    private String decisionBy;

    @Column(columnDefinition = "TEXT")
    private String decisionNote;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
