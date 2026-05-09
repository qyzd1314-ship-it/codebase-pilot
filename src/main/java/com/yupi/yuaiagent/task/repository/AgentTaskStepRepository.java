package com.yupi.yuaiagent.task.repository;

import com.yupi.yuaiagent.task.entity.AgentTaskStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AgentTaskStepRepository extends JpaRepository<AgentTaskStep, Long> {

    List<AgentTaskStep> findByTaskIdOrderByStepSeqAsc(Long taskId);

    @Transactional
    void deleteByTaskId(Long taskId);
}
