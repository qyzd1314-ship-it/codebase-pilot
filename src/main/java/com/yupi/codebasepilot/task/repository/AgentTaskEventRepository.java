package com.yupi.codebasepilot.task.repository;

import com.yupi.codebasepilot.task.entity.AgentTaskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTaskEventRepository extends JpaRepository<AgentTaskEvent, Long> {

    List<AgentTaskEvent> findByTaskIdOrderByCreatedAtAsc(Long taskId);
}
