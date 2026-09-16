package com.karyakina.schedule.service.generator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record SolverInput(
        List<GroupRef> groups,
        List<TeacherRef> teachers,
        List<GenerationGrid.Room> rooms,
        List<Demand> demands,
        SolverConfig config
) {

    public record GroupRef(long id, String name, int studentCount, int maxPairsPerDay, int maxWeeklyPairs,
                           Set<Integer> blockedSlots) {
        public GroupRef {
            blockedSlots = blockedSlots == null ? Set.of() : Set.copyOf(blockedSlots);
        }
    }

    public record TeacherRef(long id, String fullName, int maxPairsPerDay, int maxWeeklyPairs,
                             Set<Integer> preferredDays) {
        public TeacherRef {
            preferredDays = preferredDays == null ? Set.of() : Set.copyOf(preferredDays);
        }
    }

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
