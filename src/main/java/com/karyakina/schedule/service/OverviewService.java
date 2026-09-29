package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Curatorship;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.dto.DashboardMetricsDto;
import com.karyakina.schedule.dto.DepartmentHoursDto;
import com.karyakina.schedule.dto.TeacherLoadSummaryDto;
import com.karyakina.schedule.domain.SpecialEvent;
import com.karyakina.schedule.repository.CuratorshipRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.SpecialEventRepository;
import com.karyakina.schedule.repository.UnresolvedRescheduleRepository;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OverviewService {

    private final ScheduleRepository scheduleRepository;
    private final TeacherLoadService teacherLoadService;
    private final UnresolvedRescheduleRepository unresolvedRescheduleRepository;
    private final CuratorshipRepository curatorshipRepository;
    private final SpecialEventRepository specialEventRepository;

    public int countConductedExams(int academicYearStart) {
        LocalDate today = LocalDate.now();
        return (int) specialEventRepository.findByAcademicYear(academicYearStart).stream()
                .filter(e -> e.getType() == SpecialEvent.Type.EXAM && !e.getStartDate().isAfter(today))
                .count();
    }

    public int currentAcademicWeek(int academicYearStart) {
        int semester = AcademicYearUtil.getCurrentSemester();
        LocalDate start = AcademicYearUtil.semesterStart(semester, academicYearStart);
        long daysSince = ChronoUnit.DAYS.between(start, LocalDate.now());
        int week = (int) Math.floor(daysSince / 7.0) + 1;
        return Math.max(1, Math.min(week, AcademicYearUtil.WEEKS_PER_SEMESTER));
    }

    public DashboardMetricsDto buildDashboardMetrics(int academicYearStart) {
        int currentWeek = currentAcademicWeek(academicYearStart);
        List<Schedule> all = scheduleRepository.findByAcademicYear(academicYearStart);

        List<Schedule> thisWeek = filterForWeek(all, currentWeek);
        List<Schedule> lastWeek = filterForWeek(all, currentWeek - 1);

        int gaps = countGaps(thisWeek);
        int conflicts = countConflicts(thisWeek);

        List<TeacherLoad> loads = teacherLoadService.findByYear(academicYearStart);
        double avgLoad = loads.stream()
                .filter(l -> l.getPlannedHours() != null && l.getPlannedHours() > 0)
                .mapToDouble(l -> Math.min(100.0, l.getReadHours() * 100.0 / l.getPlannedHours()))
                .average().orElse(0.0);

        int unresolved = unresolvedRescheduleRepository.findByAcademicYearOrderByOriginalDateAsc(academicYearStart).size();

        return DashboardMetricsDto.builder()
                .pairsThisWeek(thisWeek.size())
                .pairsDelta(thisWeek.size() - lastWeek.size())
                .freeGapsCount(gaps)
                .conflictsCount(conflicts)
                .avgLoadPercent(Math.round(avgLoad * 10) / 10.0)
                .unresolvedCount(unresolved)
                .currentWeek(currentWeek)
                .build();
    }

    private List<Schedule> filterForWeek(List<Schedule> all, int week) {
        return all.stream()
                .filter(s -> s.getAcademicWeek() == null || s.getAcademicWeek() == week)
                .collect(Collectors.toList());
    }

    private int countGaps(List<Schedule> weekSchedules) {
        Map<DayOfWeek, List<LocalTime>> slotsByDay = new EnumMap<>(DayOfWeek.class);
        for (Schedule s : weekSchedules) {
            List<LocalTime> slots = slotsByDay.computeIfAbsent(s.getDayOfWeek(), d -> new ArrayList<>());
            if (!slots.contains(s.getStartTime())) slots.add(s.getStartTime());
        }
        slotsByDay.values().forEach(Collections::sort);

        Map<Long, Map<DayOfWeek, TreeSet<Integer>>> byGroup = new HashMap<>();
        for (Schedule s : weekSchedules) {
            if (s.getTeacherLoad() == null || s.getTeacherLoad().getGroup() == null) continue;
            Long groupId = s.getTeacherLoad().getGroup().getId();
            int idx = slotsByDay.get(s.getDayOfWeek()).indexOf(s.getStartTime());
            byGroup.computeIfAbsent(groupId, g -> new HashMap<>())
                    .computeIfAbsent(s.getDayOfWeek(), d -> new TreeSet<>())
                    .add(idx);
        }

        int gaps = 0;
        for (Map<DayOfWeek, TreeSet<Integer>> byDay : byGroup.values()) {
            for (TreeSet<Integer> indexes : byDay.values()) {
                if (indexes.size() < 2) continue;
                int span = indexes.last() - indexes.first() + 1;
                gaps += span - indexes.size();
            }
        }
        return gaps;
    }

    private int countConflicts(List<Schedule> weekSchedules) {
        Map<String, Long> roomKeys = weekSchedules.stream().collect(Collectors.groupingBy(
                s -> s.getDayOfWeek() + "|" + s.getStartTime() + "|" + s.getClassroom(),
                Collectors.counting()));
        long roomConflicts = roomKeys.values().stream().filter(c -> c > 1).count();

        Map<String, Long> teacherKeys = weekSchedules.stream()
                .filter(s -> s.getTeacherLoad() != null && s.getTeacherLoad().getTeacher() != null)
                .collect(Collectors.groupingBy(
                        s -> s.getDayOfWeek() + "|" + s.getStartTime() + "|" + s.getTeacherLoad().getTeacher().getId(),
                        Collectors.counting()));
        long teacherConflicts = teacherKeys.values().stream().filter(c -> c > 1).count();

        return (int) (roomConflicts + teacherConflicts);
    }

    public List<DepartmentHoursDto> buildDepartmentHours(int academicYearStart) {
        List<TeacherLoad> loads = teacherLoadService.findByYear(academicYearStart);
        Map<String, int[]> byDept = new TreeMap<>();
        for (TeacherLoad l : loads) {
            String dept = (l.getTeacher() != null && l.getTeacher().getDepartment() != null && !l.getTeacher().getDepartment().isBlank())
                    ? l.getTeacher().getDepartment() : "Без кафедры";
            int[] hours = byDept.computeIfAbsent(dept, d -> new int[2]);
            hours[0] += nz(l.getPlannedHours());
            hours[1] += nz(l.getReadHours());
        }
        List<DepartmentHoursDto> result = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byDept.entrySet()) {
            int planned = e.getValue()[0];
            int conducted = e.getValue()[1];
            double pct = planned > 0 ? Math.round(conducted * 1000.0 / planned) / 10.0 : 0.0;
            result.add(DepartmentHoursDto.builder()
                    .department(e.getKey())
                    .plannedHours(planned)
                    .conductedHours(conducted)
                    .completionPercent(pct)
                    .build());
        }
        result.sort((a, b) -> Integer.compare(b.getPlannedHours(), a.getPlannedHours()));
        return result;
    }

    public List<TeacherLoadSummaryDto> buildTeacherLoadSummary(int academicYearStart) {
        List<TeacherLoad> loads = teacherLoadService.findByYear(academicYearStart);
        Map<Long, Double> curatorshipByTeacher = curatorshipRepository.findAll().stream()
                .filter(c -> c.getTeacher() != null && c.getHours() != null)
                .collect(Collectors.groupingBy(c -> c.getTeacher().getId(),
                        Collectors.summingDouble(Curatorship::getHours)));

        Map<Long, List<TeacherLoad>> byTeacher = loads.stream()
                .filter(l -> l.getTeacher() != null)
                .collect(Collectors.groupingBy(l -> l.getTeacher().getId()));

        Map<Long, Double> plannedPerRateByTeacher = new HashMap<>();
        for (Map.Entry<Long, List<TeacherLoad>> entry : byTeacher.entrySet()) {
            Teacher teacher = entry.getValue().get(0).getTeacher();
            int planned = entry.getValue().stream().mapToInt(l -> nz(l.getPlannedHours())).sum();
            double rate = teacher.getRate() != null && teacher.getRate() > 0 ? teacher.getRate() : 1.0;
            plannedPerRateByTeacher.put(entry.getKey(), planned / rate);
        }
        double avgPlannedPerRate = plannedPerRateByTeacher.values().stream()
                .mapToDouble(Double::doubleValue).average().orElse(1.0);
        if (avgPlannedPerRate <= 0) avgPlannedPerRate = 1.0;

        List<TeacherLoadSummaryDto> result = new ArrayList<>();
        for (Map.Entry<Long, List<TeacherLoad>> entry : byTeacher.entrySet()) {
            Teacher teacher = entry.getValue().get(0).getTeacher();
            int planned = entry.getValue().stream().mapToInt(l -> nz(l.getPlannedHours())).sum();
            int conducted = entry.getValue().stream().mapToInt(l -> nz(l.getReadHours())).sum();
            double curatorship = curatorshipByTeacher.getOrDefault(entry.getKey(), 0.0);

            double ratePercent = Math.round(plannedPerRateByTeacher.get(entry.getKey()) * 1000.0 / avgPlannedPerRate) / 10.0;

            String status;
            if (ratePercent > 130) status = "overload";
            else if (ratePercent < 70) status = "underload";
            else status = "normal";

            result.add(TeacherLoadSummaryDto.builder()
                    .teacherId(entry.getKey())
                    .teacherName(teacher.getFullName())
                    .department(teacher.getDepartment())
                    .plannedHours(planned)
                    .conductedHours(conducted)
                    .remainingHours(planned - conducted)
                    .curatorshipHours(curatorship)
                    .ratePercent(ratePercent)
                    .status(status)
                    .build());
        }
        result.sort(Comparator.comparing(TeacherLoadSummaryDto::getTeacherName));
        return result;
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
