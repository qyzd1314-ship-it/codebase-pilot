package com.yupi.codebasepilot.tools.repository;

import com.yupi.codebasepilot.tools.entity.ManusSessionToolCallEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManusSessionToolCallRepository extends JpaRepository<ManusSessionToolCallEntity, Long> {

    List<ManusSessionToolCallEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    void deleteBySessionId(String sessionId);
}
