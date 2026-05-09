package com.yupi.yuaiagent.tools.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "manus_session")
@Getter
@Setter
public class ManusSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String sessionId;

    @Column(length = 128)
    private String displayName;

    @Column(length = 512)
    private String tags;

    @Column(length = 512)
    private String workspacePath;

    @Column(columnDefinition = "TEXT")
    private String messageSnapshot;

    @Column(length = 32)
    private String status;

    @Column(nullable = false)
    private Boolean pinned = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime lastActiveAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.lastActiveAt == null) {
            this.lastActiveAt = now;
        }
    }

    @PreUpdate
    public void onUpdate() {
        LocalDateTime now = LocalDateTime.now();
        this.updatedAt = now;
        this.lastActiveAt = now;
    }
}
