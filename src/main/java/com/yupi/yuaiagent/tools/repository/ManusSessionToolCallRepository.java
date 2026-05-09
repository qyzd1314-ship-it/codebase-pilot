package com.yupi.yuaiagent.tools.repository;

import com.yupi.yuaiagent.tools.entity.ManusSessionToolCallEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManusSessionToolCallRepository extends JpaRepository<ManusSessionToolCallEntity, Long> {

    List<ManusSessionToolCallEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    void deleteBySessionId(String sessionId);
}
