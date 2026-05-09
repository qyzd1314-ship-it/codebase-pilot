package com.yupi.codebasepilot.tools.repository;

import com.yupi.codebasepilot.tools.entity.ManusSessionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManusSessionEventRepository extends JpaRepository<ManusSessionEventEntity, Long> {

    List<ManusSessionEventEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    void deleteBySessionId(String sessionId);
}
