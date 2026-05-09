package com.yupi.yuaiagent.tools.repository;

import com.yupi.yuaiagent.tools.entity.ManusSessionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManusSessionEventRepository extends JpaRepository<ManusSessionEventEntity, Long> {

    List<ManusSessionEventEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    void deleteBySessionId(String sessionId);
}
