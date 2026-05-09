package com.yupi.yuaiagent.task.repository;

import com.yupi.yuaiagent.task.entity.AgentTaskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTaskEventRepository extends JpaRepository<AgentTaskEvent, Long> {

    List<AgentTaskEvent> findByTaskIdOrderByCreatedAtAsc(Long taskId);
}
