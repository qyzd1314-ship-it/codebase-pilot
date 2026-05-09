package com.yupi.yuaiagent.tools.repository;

import com.yupi.yuaiagent.tools.entity.ManusSessionApprovalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManusSessionApprovalRepository extends JpaRepository<ManusSessionApprovalEntity, Long> {

    Optional<ManusSessionApprovalEntity> findBySessionIdAndToolName(String sessionId, String toolName);

    List<ManusSessionApprovalEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    void deleteBySessionId(String sessionId);
}
