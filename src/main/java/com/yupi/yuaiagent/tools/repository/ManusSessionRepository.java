package com.yupi.yuaiagent.tools.repository;

import com.yupi.yuaiagent.tools.entity.ManusSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManusSessionRepository extends JpaRepository<ManusSession, Long> {

    Optional<ManusSession> findBySessionId(String sessionId);

    List<ManusSession> findAllByOrderByLastActiveAtDesc();

    List<ManusSession> findBySessionIdIn(List<String> sessionIds);
}
