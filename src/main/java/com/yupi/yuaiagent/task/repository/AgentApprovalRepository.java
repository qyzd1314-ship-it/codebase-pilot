package com.yupi.yuaiagent.task.repository;

import com.yupi.yuaiagent.task.entity.AgentApproval;
import com.yupi.yuaiagent.task.enums.AgentApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentApprovalRepository extends JpaRepository<AgentApproval, Long> {

    List<AgentApproval> findByTaskIdOrderByCreatedAtDesc(Long taskId);

    Optional<AgentApproval> findFirstByTaskIdAndStepIdAndStatusOrderByCreatedAtDesc(Long taskId, Long stepId, AgentApprovalStatus status);

    List<AgentApproval> findByTaskIdAndStatusOrderByCreatedAtDesc(Long taskId, AgentApprovalStatus status);

    void deleteByTaskId(Long taskId);
}
