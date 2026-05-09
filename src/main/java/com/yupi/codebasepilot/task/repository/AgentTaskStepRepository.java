package com.yupi.codebasepilot.task.repository;

import com.yupi.codebasepilot.task.entity.AgentTaskStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AgentTaskStepRepository extends JpaRepository<AgentTaskStep, Long> {

    List<AgentTaskStep> findByTaskIdOrderByStepSeqAsc(Long taskId);

    @Transactional
    void deleteByTaskId(Long taskId);
}
