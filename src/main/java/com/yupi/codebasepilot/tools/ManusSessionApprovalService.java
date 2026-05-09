package com.yupi.codebasepilot.tools;

import com.yupi.codebasepilot.tools.entity.ManusSessionApprovalEntity;
import com.yupi.codebasepilot.tools.repository.ManusSessionApprovalRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ManusSessionApprovalService {

    private final ManusSessionApprovalRepository manusSessionApprovalRepository;
    private final ManusSessionEventService manusSessionEventService;

    public ManusSessionApprovalService(ManusSessionApprovalRepository manusSessionApprovalRepository,
                                       ManusSessionEventService manusSessionEventService) {
        this.manusSessionApprovalRepository = manusSessionApprovalRepository;
        this.manusSessionEventService = manusSessionEventService;
    }

    public ApprovalCheckResult ensureApproved(String sessionId, String toolName, String reason) {
        ManusSessionApprovalEntity approval = manusSessionApprovalRepository.findBySessionIdAndToolName(sessionId, toolName)
                .orElseGet(() -> createPendingApproval(sessionId, toolName, reason));
        if (approval.getStatus() == ManusSessionApprovalEntity.ApprovalStatus.APPROVED) {
            return new ApprovalCheckResult(true, false, toRecord(approval));
        }
        return new ApprovalCheckResult(
                false,
                approval.getStatus() == ManusSessionApprovalEntity.ApprovalStatus.REJECTED,
                toRecord(approval)
        );
    }

    public ManusToolApproval approve(String sessionId, String toolName, String approvedBy, String decisionNote) {
        ManusSessionApprovalEntity approval = manusSessionApprovalRepository.findBySessionIdAndToolName(sessionId, toolName)
                .orElseGet(() -> createPendingApproval(sessionId, toolName, null));
        approval.setStatus(ManusSessionApprovalEntity.ApprovalStatus.APPROVED);
        approval.setApprovedBy(approvedBy);
        approval.setDecisionNote(decisionNote);
        approval.setDecidedAt(LocalDateTime.now());
        ManusSessionApprovalEntity saved = manusSessionApprovalRepository.save(approval);
        manusSessionEventService.recordEvent(sessionId, "APPROVAL_APPROVED", "Tool approved", toolName + ": " + decisionNote);
        return toRecord(saved);
    }

    public ManusToolApproval reject(String sessionId, String toolName, String approvedBy, String decisionNote) {
        ManusSessionApprovalEntity approval = manusSessionApprovalRepository.findBySessionIdAndToolName(sessionId, toolName)
                .orElseGet(() -> createPendingApproval(sessionId, toolName, null));
        approval.setStatus(ManusSessionApprovalEntity.ApprovalStatus.REJECTED);
        approval.setApprovedBy(approvedBy);
        approval.setDecisionNote(decisionNote);
        approval.setDecidedAt(LocalDateTime.now());
        ManusSessionApprovalEntity saved = manusSessionApprovalRepository.save(approval);
        manusSessionEventService.recordEvent(sessionId, "APPROVAL_REJECTED", "Tool rejected", toolName + ": " + decisionNote);
        return toRecord(saved);
    }

    public List<ManusToolApproval> listApprovals(String sessionId) {
        return manusSessionApprovalRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toRecord)
                .toList();
    }

    public String buildApprovalMessage(ManusToolApproval approval) {
        return "APPROVAL_REQUIRED|sessionId=" + approval.sessionId()
                + "|toolName=" + approval.toolName()
                + "|reason=" + sanitize(approval.reason());
    }

    public String buildRejectedMessage(ManusToolApproval approval) {
        return "APPROVAL_REJECTED|sessionId=" + approval.sessionId()
                + "|toolName=" + approval.toolName()
                + "|reason=" + sanitize(approval.reason())
                + "|decisionNote=" + sanitize(approval.decisionNote());
    }

    private ManusSessionApprovalEntity createPendingApproval(String sessionId, String toolName, String reason) {
        ManusSessionApprovalEntity approval = new ManusSessionApprovalEntity();
        approval.setSessionId(sessionId);
        approval.setToolName(toolName);
        approval.setStatus(ManusSessionApprovalEntity.ApprovalStatus.PENDING);
        approval.setReason(reason);
        ManusSessionApprovalEntity saved = manusSessionApprovalRepository.save(approval);
        manusSessionEventService.recordEvent(sessionId, "APPROVAL_REQUIRED", "Tool approval required", toolName + ": " + reason);
        return saved;
    }

    private ManusToolApproval toRecord(ManusSessionApprovalEntity entity) {
        return new ManusToolApproval(
                entity.getSessionId(),
                entity.getToolName(),
                ApprovalStatus.valueOf(entity.getStatus().name()),
                entity.getReason(),
                entity.getApprovedBy(),
                entity.getDecisionNote(),
                entity.getCreatedAt(),
                entity.getDecidedAt()
        );
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "/").replace("\r", " ").replace("\n", " ");
    }

    public record ApprovalCheckResult(boolean approved, boolean rejected, ManusToolApproval approval) {
    }

    public record ManusToolApproval(
            String sessionId,
            String toolName,
            ApprovalStatus status,
            String reason,
            String approvedBy,
            String decisionNote,
            LocalDateTime createdAt,
            LocalDateTime decidedAt
    ) {
    }

    public enum ApprovalStatus {
        PENDING,
        APPROVED,
        REJECTED
    }
}
