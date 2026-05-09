package com.yupi.codebasepilot.task.repository;

import com.yupi.codebasepilot.task.entity.AgentArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentArtifactRepository extends JpaRepository<AgentArtifact, Long> {

    List<AgentArtifact> findByTaskIdOrderByCreatedAtDesc(Long taskId);

    Optional<AgentArtifact> findByTaskIdAndRelativePath(Long taskId, String relativePath);

    Optional<AgentArtifact> findByIdAndTaskId(Long id, Long taskId);
}
