package com.karyakina.schedule.service;

import com.karyakina.schedule.dto.GenerationRequestDTO;
import com.karyakina.schedule.dto.MissingResourceRequest;
import com.karyakina.schedule.service.generator.SolverResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GenerationSessionStore {

    // черновик можно открыть снова пока сессия жива
    private static final long TTL_MINUTES = 12 * 60;
    private static final int MAX_SESSIONS = 200;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public static final class Session {
        private final String id = UUID.randomUUID().toString();
        private final Instant createdAt = Instant.now();
        private volatile Instant touchedAt = Instant.now();

        private GenerationRequestDTO request;
        private Integer academicYear;

        private final Map<Long, Integer> approvedHours = new HashMap<>();
        private final Map<Long, Long> assignedTeachers = new HashMap<>();
        private final Set<Long> skippedLoads = new HashSet<>();
        private final Map<Long, List<com.karyakina.schedule.domain.SpecialEvent>> eventsByGroup = new HashMap<>();
        private final Map<Long, Integer> groupWeekLimitOverrides = new HashMap<>();
        private final Set<Long> dismissedCapacityWarnings = new HashSet<>();
        private final Set<String> answeredIssueKeys = new HashSet<>();
        private final Set<Long> rebuildGroupIds = new HashSet<>();

        private Integer maxPairsPerDayGroup;
        private Integer maxSameSubjectPerDay;

        private final Map<String, MissingResourceRequest> openIssues = new LinkedHashMap<>();
        private final List<SolverResult.PlacedPair> draft = new ArrayList<>();
        private final List<MissingResourceRequest> lastIssues = new ArrayList<>();
        private final List<String> lastWarnings = new ArrayList<>();
        private final Map<String, Integer> lastMetrics = new LinkedHashMap<>();
        private boolean persisted;
        private boolean userDraft;
        private Long baseFingerprint;
        private volatile com.karyakina.schedule.dto.GenerationResultDTO lastResult;

        public String getId() {
            return id;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public GenerationRequestDTO getRequest() {
            return request;
        }

        public void setRequest(GenerationRequestDTO request) {
            this.request = request;
        }

        public Integer getAcademicYear() {
            return academicYear;
        }

        public void setAcademicYear(Integer academicYear) {
            this.academicYear = academicYear;
        }

        public Map<Long, Integer> getApprovedHours() {
            return approvedHours;
        }

        public Map<Long, Long> getAssignedTeachers() {
            return assignedTeachers;
        }

        public Map<Long, List<com.karyakina.schedule.domain.SpecialEvent>> getEventsByGroup() {
            return eventsByGroup;
        }

        public Set<Long> getSkippedLoads() {
            return skippedLoads;
        }

        public Map<Long, Integer> getGroupWeekLimitOverrides() {
            return groupWeekLimitOverrides;
        }

        public Set<String> getAnsweredIssueKeys() {
            return answeredIssueKeys;
        }

        public Set<Long> getDismissedCapacityWarnings() {
            return dismissedCapacityWarnings;
        }

        public Set<Long> getRebuildGroupIds() {
            return rebuildGroupIds;
        }

        public Integer getMaxPairsPerDayGroup() {
            return maxPairsPerDayGroup;
        }

        public void setMaxPairsPerDayGroup(Integer value) {
            this.maxPairsPerDayGroup = value;
        }

        public Integer getMaxSameSubjectPerDay() {
            return maxSameSubjectPerDay;
        }

        public void setMaxSameSubjectPerDay(Integer value) {
            this.maxSameSubjectPerDay = value;
        }

        public Map<String, MissingResourceRequest> getOpenIssues() {
            return openIssues;
        }

        public List<SolverResult.PlacedPair> getDraft() {
            return draft;
        }

        public List<MissingResourceRequest> getLastIssues() {
            return lastIssues;
        }

        public List<String> getLastWarnings() {
            return lastWarnings;
        }

        public Map<String, Integer> getLastMetrics() {
            return lastMetrics;
        }

        public boolean isPersisted() {
            return persisted;
        }

        public void setPersisted(boolean persisted) {
            this.persisted = persisted;
        }

        public void touch() {
            this.touchedAt = Instant.now();
        }

        public Instant getTouchedAt() {
            return touchedAt;
        }

        public boolean isUserDraft() {
            return userDraft;
        }

        public void setUserDraft(boolean userDraft) {
            this.userDraft = userDraft;
        }

        public Long getBaseFingerprint() {
            return baseFingerprint;
        }

        public void setBaseFingerprint(Long baseFingerprint) {
            this.baseFingerprint = baseFingerprint;
        }

        public com.karyakina.schedule.dto.GenerationResultDTO getLastResult() {
            return lastResult;
        }

        public void setLastResult(com.karyakina.schedule.dto.GenerationResultDTO lastResult) {
            this.lastResult = lastResult;
        }

        boolean expired() {
            return touchedAt.plusSeconds(TTL_MINUTES * 60).isBefore(Instant.now());
        }
    }

    public Session create(GenerationRequestDTO request, Integer academicYear) {
        purgeExpired();
        Session session = new Session();
        session.setRequest(request);
        session.setAcademicYear(academicYear);
        if (request != null && request.grid() != null) {
            session.setMaxPairsPerDayGroup(request.grid().maxPairsPerDayGroup());
            session.setMaxSameSubjectPerDay(request.grid().maxSameSubjectPerDay());
        }
        sessions.put(session.getId(), session);
        return session;
    }

    public Optional<Session> find(String sessionId) {
        purgeExpired();
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        session.touch();
        return Optional.of(session);
    }

    // последний несохранённый черновик из окна автосоставления чтобы его можно было открыть снова
    public Optional<Session> latestDraft(int academicYear) {
        purgeExpired();
        return sessions.values().stream()
                .filter(s -> s.isUserDraft() && !s.isPersisted() && s.getLastResult() != null
                        && Integer.valueOf(academicYear).equals(s.getAcademicYear())
                        && s.getLastResult().status() != com.karyakina.schedule.dto.GenerationResultDTO.Status.FAILED
                        && (!s.getDraft().isEmpty() || s.getLastResult().hasBlocking()))
                .max(java.util.Comparator.comparing(Session::getTouchedAt));
    }

    public void drop(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    private void purgeExpired() {
        sessions.values().removeIf(Session::expired);
        if (sessions.size() > MAX_SESSIONS) {
            sessions.entrySet().stream()
                    .sorted((a, b) -> a.getValue().getCreatedAt().compareTo(b.getValue().getCreatedAt()))
                    .limit(sessions.size() - MAX_SESSIONS)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(sessions::remove);
        }
    }
}
