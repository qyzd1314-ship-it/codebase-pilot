package com.yupi.yuaiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.tools.dto.ManusSessionDto;
import com.yupi.yuaiagent.tools.entity.ManusSession;
import com.yupi.yuaiagent.tools.repository.ManusSessionRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.Locale;
import java.util.Arrays;
import java.util.List;

@Service
public class ManusSessionQueryService {

    private final ManusSessionRepository manusSessionRepository;
    private final ObjectMapper objectMapper;

    public ManusSessionQueryService(ManusSessionRepository manusSessionRepository, ObjectMapper objectMapper) {
        this.manusSessionRepository = manusSessionRepository;
        this.objectMapper = objectMapper;
    }

    public List<ManusSessionDto> listSessions() {
        return manusSessionRepository.findAllByOrderByLastActiveAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    public List<ManusSessionDto> listSessions(String keyword, String status, String sortBy, String tag) {
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedStatus = normalizeStatus(status);
        String normalizedSortBy = normalizeSortBy(sortBy);
        String normalizedTag = normalizeTag(tag);
        return manusSessionRepository.findAllByOrderByLastActiveAtDesc().stream()
                .map(this::toDto)
                .filter(session -> matchesKeyword(session, normalizedKeyword))
                .filter(session -> matchesStatus(session, normalizedStatus))
                .filter(session -> matchesTag(session, normalizedTag))
                .sorted(buildComparator(normalizedSortBy))
                .toList();
    }

    public ManusSessionDto getSession(String sessionId) {
        ManusSession manusSession = manusSessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        return toDto(manusSession);
    }

    private ManusSessionDto toDto(ManusSession manusSession) {
        return ManusSessionDto.builder()
                .sessionId(manusSession.getSessionId())
                .displayName(resolveDisplayName(manusSession))
                .tags(parseTags(manusSession.getTags()))
                .status(manusSession.getStatus())
                .pinned(Boolean.TRUE.equals(manusSession.getPinned()))
                .workspacePath(manusSession.getWorkspacePath())
                .messageCount(countMessages(manusSession.getMessageSnapshot()))
                .createdAt(manusSession.getCreatedAt())
                .updatedAt(manusSession.getUpdatedAt())
                .lastActiveAt(manusSession.getLastActiveAt())
                .build();
    }

    private int countMessages(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode jsonNode = objectMapper.readTree(snapshotJson);
            return jsonNode.isArray() ? jsonNode.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String resolveDisplayName(ManusSession manusSession) {
        if (manusSession.getDisplayName() == null || manusSession.getDisplayName().isBlank()) {
            return manusSession.getSessionId();
        }
        return manusSession.getDisplayName();
    }

    private boolean matchesKeyword(ManusSessionDto session, String keyword) {
        if (keyword == null) {
            return true;
        }
        return containsIgnoreCase(session.getSessionId(), keyword)
                || containsIgnoreCase(session.getDisplayName(), keyword)
                || containsIgnoreCase(session.getWorkspacePath(), keyword)
                || containsIgnoreCase(String.join(",", session.getTags()), keyword);
    }

    private boolean matchesStatus(ManusSessionDto session, String status) {
        if (status == null) {
            return true;
        }
        return status.equalsIgnoreCase(session.getStatus());
    }

    private boolean matchesTag(ManusSessionDto session, String tag) {
        if (tag == null) {
            return true;
        }
        return session.getTags().stream()
                .map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(item -> item.equals(tag));
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSortBy(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "LAST_ACTIVE_DESC";
        }
        return sortBy.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        return tag.trim().toLowerCase(Locale.ROOT);
    }

    private Comparator<ManusSessionDto> buildComparator(String sortBy) {
        Comparator<ManusSessionDto> pinnedFirst = Comparator
                .comparing(ManusSessionDto::getPinned, Comparator.nullsLast(Boolean::compareTo))
                .reversed();
        Comparator<ManusSessionDto> contentComparator = switch (sortBy) {
            case "MESSAGE_COUNT_DESC" -> Comparator
                    .comparing(ManusSessionDto::getMessageCount, Comparator.nullsLast(Integer::compareTo))
                    .reversed()
                    .thenComparing(ManusSessionDto::getLastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()));
            case "NAME_ASC" -> Comparator
                    .comparing((ManusSessionDto session) ->
                                    session.getDisplayName() == null ? "" : session.getDisplayName().toLowerCase(Locale.ROOT))
                    .thenComparing(ManusSessionDto::getLastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()));
            case "CREATED_DESC" -> Comparator
                    .comparing(ManusSessionDto::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ManusSessionDto::getLastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator
                    .comparing(ManusSessionDto::getLastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ManusSessionDto::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return pinnedFirst.thenComparing(contentComparator);
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .distinct()
                .toList();
    }
}
