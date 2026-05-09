package com.yupi.codebasepilot.task.repository;

import com.yupi.codebasepilot.task.entity.AgentToolCall;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentToolCallRepository extends JpaRepository<AgentToolCall, Long> {

    List<AgentToolCall> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    List<AgentToolCall> findByStepIdOrderByCreatedAtAsc(Long stepId);
}
