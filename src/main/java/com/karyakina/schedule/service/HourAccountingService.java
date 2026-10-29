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

    public record LoadProgress(int expectedHours, String status, String label) {
    }

    // сколько должно быть вычитано к этому часу по расписанию и как с этим сходится вычитанное
    public Map<Long, LoadProgress> progressToDate(int academicYear) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        LocalDate from = AcademicYearUtil.semesterStart(1, academicYear);
        LocalDate yearEnd = AcademicYearUtil.semesterEnd(2, academicYear);
        LocalDate to = today.isAfter(yearEnd) ? yearEnd : today;

        Map<Long, List<Schedule>> schedulesByLoad = new HashMap<>();
        for (Schedule s : scheduleRepository.findByAcademicYear(academicYear)) {
            if (s.getTeacherLoad() == null) continue;
            schedulesByLoad.computeIfAbsent(s.getTeacherLoad().getId(), k -> new ArrayList<>()).add(s);
        }
        Map<String, LessonInstance> instanceByKey = new HashMap<>();
        if (!to.isBefore(from)) {
            for (LessonInstance li : lessonInstanceRepository.findByLessonDateBetween(from, to)) {
                if (li.getSchedule() != null) instanceByKey.put(li.getSchedule().getId() + "_" + li.getLessonDate(), li);
            }
        }

        Map<Long, Integer> expected = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!AcademicYearUtil.isWorkDay(d) || AcademicYearUtil.isVacation(d)) continue;
            int week = AcademicYearUtil.academicWeek(d, academicYear);
            int dateSemester = AcademicYearUtil.semesterOfDate(d);
            for (Map.Entry<Long, List<Schedule>> entry : schedulesByLoad.entrySet()) {
                for (Schedule s : entry.getValue()) {
                    if (!ScheduleOccurrence.occursOn(s, d, week, dateSemester)) continue;
                    if (d.equals(today) && s.getEndTime() != null && s.getEndTime().isAfter(now.toLocalTime())) continue;
                    LessonInstance li = instanceByKey.get(s.getId() + "_" + d);
                    if (li != null) {
                        LessonInstance.Status st = li.getStatus();
                        if (st == LessonInstance.Status.CANCELLED || st == LessonInstance.Status.INDEPENDENT_WORK) continue;
                        if (st == LessonInstance.Status.REPLACED && li.getTeacherLoad() != null
                                && !entry.getKey().equals(li.getTeacherLoad().getId())) continue;
                    }
                    int hours = li != null && li.getDurationHours() != null ? li.getDurationHours() : pairHours(s);
                    expected.merge(entry.getKey(), hours, Integer::sum);
                }
            }
        }

        int semester = AcademicYearUtil.getCurrentSemester();
        Map<Long, LoadProgress> result = new HashMap<>();
        for (TeacherLoad load : loadRepository.findByAcademicYear(academicYear)) {
            int planned = nz(load.getPlannedHours());
            int read = nz(load.getReadHours());
            int exp = expected.getOrDefault(load.getId(), 0);
            boolean scheduled = schedulesByLoad.containsKey(load.getId());
            String status;
            String label;
            int diff = exp - read;
            if (Boolean.TRUE.equals(load.getOverload())) {
                status = "OVERLOAD";
                label = "Замены";
            } else if (planned == 0) {
                status = "NO_PLAN";
                label = "Нет в тарификации";
            } else if (read >= planned) {
                status = "DONE";
                label = "Выполнено";
            } else if (load.getDiscipline() != null
                    && ScheduleGeneratorService.isPracticeOrDriving(load.getDiscipline().getName())) {
                status = "PRACTICE";
                label = "Практика";
            } else if (!scheduled && read == 0) {
                int nowHours = semester == 2 ? nz(load.getSecondSemesterHours()) : nz(load.getFirstSemesterHours());
                status = nowHours > 0 ? "NOT_SCHEDULED" : "LATER";
                label = nowHours > 0 ? "Нет в расписании" : (semester == 1 ? "Со 2 семестра" : "Не в этом семестре");
            } else if (Math.abs(diff) <= 2) {
                status = "ON_TRACK";
                label = "По графику";
            } else if (diff < 0) {
                status = "AHEAD";
                label = "Сверх графика +" + (-diff) + " ч";
            } else if (diff <= Math.max(4, exp * 0.15)) {
                status = "LAG";
                label = "Отстаёт на " + diff + " ч";
            } else {
                status = "BIG_LAG";
                label = "Отстаёт на " + diff + " ч";
            }
            result.put(load.getId(), new LoadProgress(exp, status, label));
        }
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
