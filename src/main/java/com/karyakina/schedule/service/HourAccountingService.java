package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.MonthlyRecord;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.MonthlyRecordRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.util.AcademicYearUtil;
import com.karyakina.schedule.util.ScheduleOccurrence;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class HourAccountingService {

    private final TeacherLoadRepository loadRepository;
    private final LessonInstanceRepository lessonInstanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final MonthlyRecordRepository monthlyRecordRepository;

    @Data
    @Builder
    public static class LoadHoursSummary {
        private Long loadId;
        private String teacherName;
        private String disciplineName;
        private String groupName;
        private int semester;
        private int plannedHours;
        private int conductedHours;
        private int remainingHours;
    }

    @Data
    @Builder
    public static class PeriodHoursSummary {
        private Long loadId;
        private String teacherName;
        private String disciplineName;
        private String groupName;
        private int plannedHours;
        private int conductedHours;
        private int remainingHours;
    }

    // вычитано за семестр одна формула для всех вкладок
    public int conductedInSemester(TeacherLoad load, int semester, LocalDate today) {
        return conductedInSemester(load, semester, today, monthlyRecordRepository.findByTeacherLoadId(load.getId()));
    }

    public int conductedInSemester(TeacherLoad load, int semester, LocalDate today, List<MonthlyRecord> records) {
        int inSemester = 0;
        int allMonths = 0;
        for (MonthlyRecord r : records) {
            if (r.getYear() != null && load.getAcademicYear() != null && !r.getYear().equals(load.getAcademicYear())) {
                continue;
            }
            int hours = r.getConductedHoursOrZero();
            allMonths += hours;
            int month = r.getMonth() == null ? 0 : r.getMonth();
            if (semester == 1 ? month >= 9 && month <= 12 : month >= 1 && month <= 8) inSemester += hours;
        }
        // старые ручные правки без месяца считаем в текущий семестр
        int manualExtra = Math.max(0, nz(load.getReadHours()) - allMonths);
        if (manualExtra > 0 && AcademicYearUtil.getCurrentSemester() == semester
                && AcademicYearUtil.academicYearOf(today) == nz(load.getAcademicYear())) {
            inSemester += manualExtra;
        }
        return inSemester;
    }

    public List<PeriodHoursSummary> getWeeklySummary(Integer academicYear, Long teacherId) {
        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays((today.getDayOfWeek().getValue() - 1));
        LocalDate friday = monday.plusDays(GenerationGrid.days() - 1);
        return getPeriodSummary(year, teacherId, monday, friday);
    }

    public List<PeriodHoursSummary> getMonthlySummary(Integer academicYear, Long teacherId) {
        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        return getPeriodSummary(year, teacherId, monthStart, monthEnd);
    }

    private List<PeriodHoursSummary> getPeriodSummary(int academicYear, Long teacherId, LocalDate from, LocalDate to) {
        List<TeacherLoad> loads = teacherId != null
                ? loadRepository.findByTeacherIdAndAcademicYear(teacherId, academicYear)
                : loadRepository.findByAcademicYear(academicYear);
        Map<Long, List<Schedule>> schedulesByLoad = new HashMap<>();
        for (Schedule s : scheduleRepository.findByAcademicYear(academicYear)) {
            if (s.getTeacherLoad() == null) continue;
            schedulesByLoad.computeIfAbsent(s.getTeacherLoad().getId(), k -> new ArrayList<>()).add(s);
        }
        Map<String, LessonInstance> instanceByKey = new HashMap<>();
        Map<Long, Integer> conductedByLoad = new HashMap<>();
        for (LessonInstance li : lessonInstanceRepository.findByLessonDateBetween(from, to)) {
            if (li.getSchedule() != null) instanceByKey.put(li.getSchedule().getId() + "_" + li.getLessonDate(), li);
            if (li.getTeacherLoad() != null && LessonInstanceService.isCredited(li)) {
                conductedByLoad.merge(li.getTeacherLoad().getId(), nz(li.getDurationHours()), Integer::sum);
            }
        }

        Map<Long, Integer> plannedByLoad = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (GenerationGrid.dayIndex(d.getDayOfWeek()) < 0) continue;
            if (AcademicYearUtil.isVacation(d)) continue;
            int dateSemester = AcademicYearUtil.semesterOfDate(d);
            int week = AcademicYearUtil.academicWeek(d, academicYear);
            for (Map.Entry<Long, List<Schedule>> entry : schedulesByLoad.entrySet()) {
                for (Schedule s : entry.getValue()) {
                    if (!ScheduleOccurrence.occursOn(s, d, week, dateSemester)) continue;
                    LessonInstance li = instanceByKey.get(s.getId() + "_" + d);
                    if (li != null && (li.getStatus() == LessonInstance.Status.CANCELLED
                            || li.getStatus() == LessonInstance.Status.INDEPENDENT_WORK)) continue;
                    plannedByLoad.merge(entry.getKey(), pairHours(s), Integer::sum);
                }
            }
        }

        List<PeriodHoursSummary> result = new ArrayList<>();
        for (TeacherLoad load : loads) {
            int planned = plannedByLoad.getOrDefault(load.getId(), 0);
            if (planned == 0 && !conductedByLoad.containsKey(load.getId())) continue;
            int conducted = conductedByLoad.getOrDefault(load.getId(), 0);
            result.add(PeriodHoursSummary.builder()
                    .loadId(load.getId())
                    .teacherName(teacherName(load))
                    .disciplineName(load.getDiscipline() != null ? load.getDiscipline().getName() : "")
                    .groupName(load.getGroup() != null ? load.getGroup().getName() : "")
                    .plannedHours(planned)
                    .conductedHours(conducted)
                    .remainingHours(Math.max(0, planned - conducted))
                    .build());
        }
        result.sort(Comparator.comparing(PeriodHoursSummary::getTeacherName)
                .thenComparing(PeriodHoursSummary::getDisciplineName));
        return result;
    }

    public List<LoadHoursSummary> getSummary(Integer academicYear, Integer semester, Long teacherId) {
        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        int sem = semester != null ? semester : AcademicYearUtil.getCurrentSemester();
        LocalDate today = LocalDate.now();

        List<TeacherLoad> loads = teacherId != null
                ? loadRepository.findByTeacherIdAndAcademicYear(teacherId, year)
                : loadRepository.findByAcademicYear(year);

        List<LoadHoursSummary> result = new ArrayList<>();
        for (TeacherLoad load : loads) {
            int planned = sem == 2 ? nz(load.getSecondSemesterHours()) : nz(load.getFirstSemesterHours());
            int conducted = conductedInSemester(load, sem, today);
            if (planned == 0 && conducted == 0) continue;
            result.add(LoadHoursSummary.builder()
                    .loadId(load.getId())
                    .teacherName(teacherName(load))
                    .disciplineName(load.getDiscipline() != null ? load.getDiscipline().getName() : "")
                    .groupName(load.getGroup() != null ? load.getGroup().getName() : "")
                    .semester(sem)
                    .plannedHours(planned)
                    .conductedHours(conducted)
                    .remainingHours(Math.max(0, planned - conducted))
                    .build());
        }
        result.sort(Comparator.comparing(LoadHoursSummary::getTeacherName)
                .thenComparing(LoadHoursSummary::getDisciplineName));
        return result;
    }

    static int pairHours(Schedule s) {
        long minutes = ChronoUnit.MINUTES.between(s.getStartTime(), s.getEndTime());
        return (int) Math.max(1, Math.round(minutes / 60.0));
    }

    private static String teacherName(TeacherLoad load) {
        return load.getTeacher() != null ? load.getTeacher().getFullName() : "Не назначен";
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
