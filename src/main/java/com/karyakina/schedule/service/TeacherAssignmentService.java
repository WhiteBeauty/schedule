package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class TeacherAssignmentService {

    public static class Resolution {
        public final Map<Long, Teacher> resolvedTeacherByLoadId = new HashMap<>();
        public final List<String> conflicts = new ArrayList<>();
    }

    public Resolution resolve(List<TeacherLoad> allLoadsForYear, List<Teacher> allTeachers) {
        Resolution result = new Resolution();

        Map<Long, Integer> hoursByTeacher = new HashMap<>();
        for (TeacherLoad l : allLoadsForYear) {
            if (l.getTeacher() != null) {
                hoursByTeacher.merge(l.getTeacher().getId(), nz(l.getPlannedHours()), Integer::sum);
            }
        }

        List<TeacherLoad> unassigned = allLoadsForYear.stream()
                .filter(l -> l.getTeacher() == null)
                .sorted(Comparator.comparing(l -> l.getDiscipline().getName()))
                .toList();

        for (TeacherLoad load : unassigned) {
            List<Teacher> pool = candidatePool(load, allTeachers);

            if (pool.isEmpty()) {
                result.conflicts.add(String.format(
                        "Не удалось подобрать преподавателя: дисциплина «%s», группа %s — " +
                        "нет преподавателя ни в списке кандидатов, ни с подходящей специализацией " +
                        "(заполните Teacher.specialization или колонку \"Возможные преподаватели\")",
                        load.getDiscipline().getName(), load.getGroup().getName()));
                continue;
            }

            Teacher chosen = pool.stream()
                    .min(Comparator.<Teacher>comparingInt(t -> hoursByTeacher.getOrDefault(t.getId(), 0))
                            .thenComparing(Teacher::getFullName))
                    .orElseThrow();

            result.resolvedTeacherByLoadId.put(load.getId(), chosen);
            hoursByTeacher.merge(chosen.getId(), nz(load.getPlannedHours()), Integer::sum);
        }

        return result;
    }

    private List<Teacher> candidatePool(TeacherLoad load, List<Teacher> allTeachers) {
        if (load.getCandidateTeacherNames() != null && !load.getCandidateTeacherNames().isBlank()) {
            List<Teacher> named = new ArrayList<>();
            for (String token : load.getCandidateTeacherNames().split(",")) {
                String name = token.trim();
                if (name.isEmpty()) continue;
                allTeachers.stream()
                        .filter(t -> t.getFullName().equalsIgnoreCase(name))
                        .findFirst()
                        .ifPresent(named::add);
            }
            if (!named.isEmpty()) return named;
        }

        String disciplineName = load.getDiscipline().getName();
        return allTeachers.stream()
                .filter(t -> matchesSpecialization(t, disciplineName))
                .toList();
    }

    public static boolean matchesSpecialization(Teacher teacher, String disciplineName) {
        if (teacher.getSpecialization() == null || teacher.getSpecialization().isBlank()) return false;
        if (disciplineName == null) return false;
        String discNorm = disciplineName.toLowerCase(Locale.ROOT);
        for (String token : teacher.getSpecialization().split(",")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && (discNorm.contains(t) || t.contains(discNorm))) {
                return true;
            }
        }
        return false;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
