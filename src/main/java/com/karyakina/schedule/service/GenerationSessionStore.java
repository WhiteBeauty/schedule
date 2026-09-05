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

/**
 * Сессия автосоставления живёт между запросами: администратор запускает генерацию,
 * получает вопросы, отвечает на них, генерация повторяется с учётом ответов — и только
 * потом черновик фиксируется в расписании.
 *
 * <p>Хранится в памяти приложения: сессия — это черновик на несколько минут, а не данные,
 * которые нужно переживать перезапуск. Протухшие сессии убираются лениво, при обращении.
 */
@Component
public class GenerationSessionStore {

    private static final long TTL_MINUTES = 60;
    private static final int MAX_SESSIONS = 200;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Состояние одной сессии: исходный запрос + все принятые администратором решения. */
    public static final class Session {
        private final String id = UUID.randomUUID().toString();
        private final Instant createdAt = Instant.now();
        private volatile Instant touchedAt = Instant.now();

        private GenerationRequestDTO request;
        private Integer academicYear;

        /** loadId -> утверждённые часы (ответ на расхождение файла и ручного ввода). */
        private final Map<Long, Integer> approvedHours = new HashMap<>();
        /** loadId -> преподаватель, назначенный вручную. */
        private final Map<Long, Long> assignedTeachers = new HashMap<>();
        /** Нагрузки, которые администратор решил не ставить в этом прогоне. */
        private final Set<Long> skippedLoads = new HashSet<>();
        /** teacherId -> поднятый вручную недельный лимит часов. */
        private final Map<Long, Integer> teacherHourLimits = new HashMap<>();
        /**
         * Преподаватели, чей перегруз администратор осознанно принял («Ставить в пределах
         * лимита, остаток — в предупреждения»). Без этого TEACHER_OVERLOAD поднимался бы
         * заново на каждом прогоне (лимит и реальные часы не менялись), и расписание было
         * бы невозможно сохранить — ни один ответ администратора не "гасил" вопрос.
         */
        private final Set<Long> acknowledgedTeacherOverloads = new HashSet<>();

        private Integer maxPairsPerDayGroup;
        private Integer maxSameSubjectPerDay;
        private Integer teacherMaxWeeklyHours;

        /** Открытые вопросы по requestId — нужны, чтобы понять, к чему относится ответ. */
        private final Map<String, MissingResourceRequest> openIssues = new LinkedHashMap<>();
        /** Последний черновик: то, что будет сохранено при фиксации. */
        private final List<SolverResult.PlacedPair> draft = new ArrayList<>();
        /** Вопросы и предупреждения последнего прогона — чтобы не пересчитывать всё заново при фиксации. */
        private final List<MissingResourceRequest> lastIssues = new ArrayList<>();
        private final List<String> lastWarnings = new ArrayList<>();
        private final Map<String, Integer> lastMetrics = new LinkedHashMap<>();
        private boolean persisted;

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

        public Set<Long> getSkippedLoads() {
            return skippedLoads;
        }

        public Map<Long, Integer> getTeacherHourLimits() {
            return teacherHourLimits;
        }

        public Set<Long> getAcknowledgedTeacherOverloads() {
            return acknowledgedTeacherOverloads;
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

        public Integer getTeacherMaxWeeklyHours() {
            return teacherMaxWeeklyHours;
        }

        public void setTeacherMaxWeeklyHours(Integer value) {
            this.teacherMaxWeeklyHours = value;
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
            session.setTeacherMaxWeeklyHours(request.grid().teacherMaxWeeklyHours());
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
