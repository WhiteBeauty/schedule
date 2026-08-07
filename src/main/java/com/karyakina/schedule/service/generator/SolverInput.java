package com.karyakina.schedule.service.generator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Снимок данных для одного прогона автосоставления. Здесь нет JPA-сущностей:
 * солвер чистый и тестируемый без БД, а перевод из {@code TeacherLoad}/{@code StudyGroup}/
 * {@code Teacher} делает {@code ScheduleGeneratorService}.
 */
public record SolverInput(
        List<GroupRef> groups,
        List<TeacherRef> teachers,
        List<GenerationGrid.Room> rooms,
        List<Demand> demands,
        SolverConfig config
) {

    /**
     * Учебная группа (монолит — пары ставятся на группу целиком).
     *
     * @param blockedSlots плоские индексы слотов, недоступных группе (обед, практика)
     */
    public record GroupRef(long id, String name, int studentCount, int maxPairsPerDay, Set<Integer> blockedSlots) {
        public GroupRef {
            blockedSlots = blockedSlots == null ? Set.of() : Set.copyOf(blockedSlots);
        }
    }

    /**
     * Преподаватель.
     *
     * @param maxWeeklyPairs жёсткий лимит пар в неделю (36 ч / 2 ч = 18 пар)
     * @param preferredDays  мягкое пожелание: индексы дней (0 = понедельник)
     */
    public record TeacherRef(long id, String fullName, int maxPairsPerDay, int maxWeeklyPairs,
                             Set<Integer> preferredDays) {
        public TeacherRef {
            preferredDays = preferredDays == null ? Set.of() : Set.copyOf(preferredDays);
        }
    }

    /**
     * Потребность = одна запись нагрузки {@code TeacherLoad}: сколько пар в неделю
     * поставить группе по дисциплине у конкретного преподавателя.
     *
     * @param academicHours часы, утверждённые к моменту генерации (после разбора расхождений)
     */
    public record Demand(
            long loadId,
            long groupId,
            long disciplineId,
            String disciplineName,
            long teacherId,
            int pairsPerWeek,
            int academicHours,
            Set<Integer> preferredDays,
            Set<Integer> preferredPairs,
            Integer maxPerDay
    ) {
        public Demand {
            preferredDays = preferredDays == null ? Set.of() : Set.copyOf(preferredDays);
            preferredPairs = preferredPairs == null ? Set.of() : Set.copyOf(preferredPairs);
            pairsPerWeek = Math.max(0, pairsPerWeek);
        }

        public int perDayLimit(SolverConfig config) {
            return maxPerDay == null || maxPerDay <= 0 ? config.maxSameSubjectPerDay() : maxPerDay;
        }

        public Demand withPairs(int pairs, int hours) {
            return new Demand(loadId, groupId, disciplineId, disciplineName, teacherId, pairs, hours,
                    preferredDays, preferredPairs, maxPerDay);
        }
    }

    public SolverInput {
        groups = groups == null ? List.of() : List.copyOf(groups);
        teachers = teachers == null ? List.of() : List.copyOf(teachers);
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
        demands = demands == null ? List.of() : List.copyOf(demands);
        config = config == null ? SolverConfig.defaults() : config;
    }

    public Map<Long, GroupRef> groupsById() {
        Map<Long, GroupRef> map = new LinkedHashMap<>();
        groups.forEach(g -> map.put(g.id(), g));
        return Collections.unmodifiableMap(map);
    }

    public Map<Long, TeacherRef> teachersById() {
        Map<Long, TeacherRef> map = new LinkedHashMap<>();
        teachers.forEach(t -> map.put(t.id(), t));
        return Collections.unmodifiableMap(map);
    }
}
