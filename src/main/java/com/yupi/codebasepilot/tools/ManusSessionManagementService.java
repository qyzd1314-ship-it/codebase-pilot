package com.yupi.codebasepilot.tools;

import com.yupi.codebasepilot.tools.dto.ManusSessionDto;
import com.yupi.codebasepilot.tools.entity.ManusSession;
import com.yupi.codebasepilot.tools.repository.ManusSessionApprovalRepository;
import com.yupi.codebasepilot.tools.repository.ManusSessionEventRepository;
import com.yupi.codebasepilot.tools.repository.ManusSessionRepository;
import com.yupi.codebasepilot.tools.repository.ManusSessionToolCallRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class ManusSessionManagementService {

    private final ManusSessionRepository manusSessionRepository;
    private final ManusSessionApprovalRepository manusSessionApprovalRepository;
    private final ManusSessionEventRepository manusSessionEventRepository;
    private final ManusSessionToolCallRepository manusSessionToolCallRepository;
    private final ManusSessionQueryService manusSessionQueryService;

    public ManusSessionManagementService(ManusSessionRepository manusSessionRepository,
                                         ManusSessionApprovalRepository manusSessionApprovalRepository,
                                         ManusSessionEventRepository manusSessionEventRepository,
                                         ManusSessionToolCallRepository manusSessionToolCallRepository,
                                         ManusSessionQueryService manusSessionQueryService) {
        this.manusSessionRepository = manusSessionRepository;
        this.manusSessionApprovalRepository = manusSessionApprovalRepository;
        this.manusSessionEventRepository = manusSessionEventRepository;
        this.manusSessionToolCallRepository = manusSessionToolCallRepository;
        this.manusSessionQueryService = manusSessionQueryService;
    }

    @Transactional
    public ManusSessionDto renameSession(String sessionId, String displayName) {
        ManusSession manusSession = getSession(sessionId);
        String nextName = (displayName == null || displayName.isBlank()) ? sessionId : displayName.trim();
        manusSession.setDisplayName(nextName);
        manusSessionRepository.save(manusSession);
        return manusSessionQueryService.getSession(sessionId);
    }

    @Transactional
    public ManusSessionDto updateTags(String sessionId, List<String> tags) {
        ManusSession manusSession = getSession(sessionId);
        manusSession.setTags(normalizeTags(tags));
        manusSessionRepository.save(manusSession);
        return manusSessionQueryService.getSession(sessionId);
    }

    @Transactional
    public ManusSessionDto archiveSession(String sessionId) {
        ManusSession manusSession = getSession(sessionId);
        manusSession.setStatus("ARCHIVED");
        manusSessionRepository.save(manusSession);
        return manusSessionQueryService.getSession(sessionId);
    }

    @Transactional
    public ManusSessionDto activateSession(String sessionId) {
        ManusSession manusSession = getSession(sessionId);
        manusSession.setStatus("ACTIVE");
        manusSessionRepository.save(manusSession);
        return manusSessionQueryService.getSession(sessionId);
    }

    @Transactional
    public ManusSessionDto clearSession(String sessionId) {
        ManusSession manusSession = getSession(sessionId);
        manusSession.setMessageSnapshot("[]");
        manusSession.setStatus("CLEARED");
        manusSessionApprovalRepository.deleteBySessionId(sessionId);
        manusSessionEventRepository.deleteBySessionId(sessionId);
        manusSessionToolCallRepository.deleteBySessionId(sessionId);
        manusSessionRepository.save(manusSession);
        return manusSessionQueryService.getSession(sessionId);
    }

    @Transactional
    public ManusSessionDto pinSession(String sessionId) {
        ManusSession manusSession = getSession(sessionId);
        manusSession.setPinned(true);
        manusSessionRepository.save(manusSession);
        return manusSessionQueryService.getSession(sessionId);
    }

    @Transactional
    public ManusSessionDto unpinSession(String sessionId) {
        ManusSession manusSession = getSession(sessionId);
        manusSession.setPinned(false);
        manusSessionRepository.save(manusSession);
        return manusSessionQueryService.getSession(sessionId);
    }

    @Transactional
    public List<ManusSessionDto> batchArchive(List<String> sessionIds) {
        return updateStatuses(sessionIds, "ARCHIVED");
    }

    @Transactional
    public List<ManusSessionDto> batchActivate(List<String> sessionIds) {
        return updateStatuses(sessionIds, "ACTIVE");
    }

    @Transactional
    public List<ManusSessionDto> batchPin(List<String> sessionIds) {
        return updatePinned(sessionIds, true);
    }

    @Transactional
    public List<ManusSessionDto> batchUnpin(List<String> sessionIds) {
        return updatePinned(sessionIds, false);
    }

    private ManusSession getSession(String sessionId) {
        return manusSessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
    }

    private List<ManusSessionDto> updateStatuses(List<String> sessionIds, String status) {
        List<ManusSession> sessions = getSessions(sessionIds);
        sessions.forEach(session -> session.setStatus(status));
        manusSessionRepository.saveAll(sessions);
        return sessions.stream()
                .map(session -> manusSessionQueryService.getSession(session.getSessionId()))
                .toList();
    }

    private List<ManusSessionDto> updatePinned(List<String> sessionIds, boolean pinned) {
        List<ManusSession> sessions = getSessions(sessionIds);
        sessions.forEach(session -> session.setPinned(pinned));
        manusSessionRepository.saveAll(sessions);
        return sessions.stream()
                .map(session -> manusSessionQueryService.getSession(session.getSessionId()))
                .toList();
    }

    private List<ManusSession> getSessions(List<String> sessionIds) {
        Set<String> orderedIds = new LinkedHashSet<>();
        if (sessionIds != null) {
            orderedIds.addAll(sessionIds);
        }
        if (orderedIds.isEmpty()) {
            throw new IllegalArgumentException("Session ids must not be empty");
        }
        List<ManusSession> sessions = manusSessionRepository.findBySessionIdIn(new ArrayList<>(orderedIds));
        if (sessions.size() != orderedIds.size()) {
            throw new NoSuchElementException("Some sessions were not found");
        }
        return sessions;
    }

    private String normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .map(tag -> tag.replace(",", " "))
                .distinct()
                .limit(12)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}
