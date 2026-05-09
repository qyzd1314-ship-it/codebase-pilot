package com.yupi.codebasepilot.task.repository;

import com.yupi.codebasepilot.task.entity.AgentTask;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {
}
