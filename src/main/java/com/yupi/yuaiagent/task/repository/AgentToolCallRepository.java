package com.yupi.yuaiagent.task.repository;

import com.yupi.yuaiagent.task.entity.AgentToolCall;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentToolCallRepository extends JpaRepository<AgentToolCall, Long> {

    List<AgentToolCall> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    List<AgentToolCall> findByStepIdOrderByCreatedAtAsc(Long stepId);
}
