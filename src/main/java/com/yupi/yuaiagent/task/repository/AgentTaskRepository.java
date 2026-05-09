package com.yupi.yuaiagent.task.repository;

import com.yupi.yuaiagent.task.entity.AgentTask;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {
}
