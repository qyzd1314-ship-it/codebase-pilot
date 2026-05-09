package com.yupi.yuaiagent.task.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "agent_task_event", indexes = {
        @Index(name = "idx_agent_task_event_task_id", columnList = "task_id"),
        @Index(name = "idx_agent_task_event_step_id", columnList = "step_id"),
        @Index(name = "idx_agent_task_event_task_step", columnList = "task_id,step_id")
})
@Getter
@Setter
public class AgentTaskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    private Long stepId;

    @Column(nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 32)
    private String eventLevel;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String eventContent;

    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
