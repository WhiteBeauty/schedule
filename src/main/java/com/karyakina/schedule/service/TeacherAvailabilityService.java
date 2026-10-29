package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Tarification;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.TarificationRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.service.generator.TeacherAvailability;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeacherAvailabilityService {

    private final TeacherRepository teacherRepository;
    private final TeacherLoadRepository loadRepository;
    private final TarificationRepository tarificationRepository;

    public List<Map<String, Object>> list(Long tarificationId, Integer semester) {
        Tarification tarification = tarificationId == null ? null : tarificationRepository.findById(tarificationId).orElse(null);
        Map<Long, Teacher> teachers = new HashMap<>();
        Map<Long, Double> pairsPerWeek = new HashMap<>();
        if (tarification == null) {
            teacherRepository.findAll().forEach(t -> teachers.put(t.getId(), t));
        } else {
            int sem = semester != null && semester == 2 ? 2 : 1;
            double weeks = AcademicYearUtil.getSemesterWeeks(sem, tarification.getAcademicYear());
            for (TeacherLoad load : loadRepository.findByTarificationId(tarification.getId())) {
                if (load.getTeacher() == null || Boolean.TRUE.equals(load.getOverload())) continue;
                teachers.putIfAbsent(load.getTeacher().getId(), load.getTeacher());
                if (load.getDiscipline() != null
                        && ScheduleGeneratorService.isPracticeOrDriving(load.getDiscipline().getName())) continue;
                Integer hours = sem == 2 ? load.getSecondSemesterHours() : load.getFirstSemesterHours();
                pairsPerWeek.merge(load.getTeacher().getId(), (hours == null ? 0 : hours) / weeks / 2.0, Double::sum);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        teachers.values().stream()
                .sorted(Comparator.comparing(t -> t.getFullName() == null ? "" : t.getFullName()))
                .forEach(t -> rows.add(toRow(t, pairsPerWeek.containsKey(t.getId())
                        ? (int) Math.round(pairsPerWeek.get(t.getId())) : null)));
        return rows;
    }

    @Transactional
    public Map<String, Object> save(Long teacherId, Map<String, Object> body) {
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new IllegalArgumentException("Преподаватель не найден"));
        Set<Integer> daysOff = numbers(body.get("daysOff"), GenerationGrid.days());
        Set<Integer> optionalDays = numbers(body.get("optionalDays"), GenerationGrid.days());
        optionalDays.removeAll(daysOff);
        Set<Integer> forbidden = numbers(body.get("forbiddenPairs"), GenerationGrid.pairsPerDay());
        Set<Integer> undesired = numbers(body.get("undesiredPairs"), GenerationGrid.pairsPerDay());
        undesired.removeAll(forbidden);
        Integer maxDays = body.get("maxDaysPerWeek") == null || body.get("maxDaysPerWeek").toString().isBlank()
                ? null : Integer.valueOf(body.get("maxDaysPerWeek").toString().trim());
        if (daysOff.size() >= GenerationGrid.days()) {
            throw new IllegalArgumentException("Должен остаться хотя бы один рабочий день");
        }
        if (forbidden.size() >= GenerationGrid.pairsPerDay()) {
            throw new IllegalArgumentException("Нельзя запретить все пары");
        }
        if (maxDays != null && (maxDays < 1 || maxDays > GenerationGrid.days())) {
            throw new IllegalArgumentException("Дней в неделю от 1 до " + GenerationGrid.days());
        }
        if (maxDays != null && maxDays >= GenerationGrid.days()) maxDays = null;
        teacher.setDaysOff(join(daysOff));
        teacher.setOptionalDays(join(optionalDays));
        teacher.setMaxDaysPerWeek(maxDays);
        teacher.setForbiddenPairs(join(forbidden));
        teacher.setUndesiredPairs(join(undesired));
        return toRow(teacherRepository.save(teacher), null);
    }

    public static Map<String, Object> toRow(Teacher t, Integer weeklyPairs) {
        TeacherAvailability availability = TeacherAvailability.of(t);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("teacherId", t.getId());
        row.put("fullName", t.getFullName());
        row.put("daysOff", oneBased(availability.daysOff()));
        row.put("optionalDays", oneBased(availability.optionalDays()));
        row.put("maxDaysPerWeek", availability.maxDays() == 0 ? null : availability.maxDays());
        row.put("forbiddenPairs", oneBased(availability.forbiddenPairs()));
        row.put("undesiredPairs", oneBased(availability.undesiredPairs()));
        row.put("slotsPerWeek", availability.slotsPerWeek());
        row.put("weeklyPairs", weeklyPairs);
        row.put("summary", availability.describe());
        return row;
    }

    private static List<Integer> oneBased(Set<Integer> zeroBased) {
        List<Integer> result = new ArrayList<>();
        new TreeSet<>(zeroBased).forEach(i -> result.add(i + 1));
        return result;
    }

    private static Set<Integer> numbers(Object raw, int limit) {
        Set<Integer> result = new TreeSet<>();
        if (raw instanceof Collection<?> list) {
            for (Object value : list) {
                try {
                    int n = Integer.parseInt(String.valueOf(value).trim());
                    if (n >= 1 && n <= limit) result.add(n);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    private static String join(Set<Integer> values) {
        if (values.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        values.forEach(v -> parts.add(String.valueOf(v)));
        return String.join(",", parts);
    }
}
