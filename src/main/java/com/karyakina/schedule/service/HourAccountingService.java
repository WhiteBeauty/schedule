package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Сверка часов (ТЗ п.6): для каждой нагрузки — сколько часов запланировано в ТЕКУЩЕМ
 * семестре, сколько реально проведено (по факту, от начала семестра до СЕГОДНЯ), и сколько
 * осталось. "Проведено" считается по {@link LessonInstance} со статусом CONFIRMED или
 * REPLACED (замена — занятие всё равно состоялось, просто другим преподавателем) — то есть
 * это фактические данные, а не то, что просто "должно было быть по шаблону расписания".
 */
@Service
@RequiredArgsConstructor
public class HourAccountingService {

    private final TeacherLoadRepository loadRepository;
    private final LessonInstanceRepository lessonInstanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final LessonInstanceService lessonInstanceService;

    @Data
    @Builder
    public static class LoadHoursSummary {
        private Long loadId;
        private String teacherName;
        private String disciplineName;
        private String groupName;
        private int semester;
        private int plannedHours;   // часы этого семестра по тарификации (firstSemesterHours/secondSemesterHours)
        private int conductedHours; // фактически проведено с начала семестра по сегодня
        private int remainingHours; // plannedHours - conductedHours, не меньше 0
    }

    /**
     * Сверка на неделю/месяц (новая вкладка): "запланировано на этот период по РАСПИСАНИЮ"
     * минус "фактически проведено (по реальным датам)" = "осталось". В отличие от
     * {@link LoadHoursSummary} (которая считает от начала семестра по сегодня), это
     * скользящее окно — конкретная календарная неделя или месяц.
     */
    @Data
    @Builder
    public static class PeriodHoursSummary {
        private Long loadId;
        private String teacherName;
        private String disciplineName;
        private String groupName;
        private int plannedHours;    // сколько часов этой нагрузки стоит в расписании на период
        private int conductedHours;  // сколько из них уже реально проведено (по датам, что миновали)
        private int remainingHours;  // plannedHours - conductedHours, не меньше 0
    }

    /** Сверка за ТЕКУЩУЮ календарную неделю (понедельник-пятница). */
    public List<PeriodHoursSummary> getWeeklySummary(Integer academicYear, Long teacherId) {
        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays((today.getDayOfWeek().getValue() - 1));
        LocalDate friday = monday.plusDays(GenerationGrid.days() - 1);
        return getPeriodSummary(year, teacherId, monday, friday);
    }

    /** Сверка за ТЕКУЩИЙ календарный месяц. */
    public List<PeriodHoursSummary> getMonthlySummary(Integer academicYear, Long teacherId) {
        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        return getPeriodSummary(year, teacherId, monthStart, monthEnd);
    }

    /**
     * Общая логика периода [from, to]: "план" — сколько раз пары этой нагрузки реально стоят
     * в {@link Schedule} на даты периода (с учётом каникул/семестра/числителя-знаменателя —
     * та же фильтрация, что и в календаре/генерации занятий), "факт" — сколько из ТЕХ ЖЕ дат,
     * что уже прошли (не позже сегодня), подтверждено по факту (CONFIRMED/REPLACED).
     */
    private List<PeriodHoursSummary> getPeriodSummary(int academicYear, Long teacherId, LocalDate from, LocalDate to) {
        List<TeacherLoad> loads = teacherId != null
                ? loadRepository.findByTeacherIdAndAcademicYear(teacherId, academicYear)
                : loadRepository.findByAcademicYear(academicYear);
        List<Schedule> allSchedules = scheduleRepository.findByAcademicYear(academicYear);
        Map<Long, List<Schedule>> schedulesByLoad = new HashMap<>();
        for (Schedule s : allSchedules) {
            if (s.getTeacherLoad() == null) continue;
            schedulesByLoad.computeIfAbsent(s.getTeacherLoad().getId(), k -> new ArrayList<>()).add(s);
        }

        LocalDate today = LocalDate.now();
        Map<Long, Integer> plannedByLoad = new HashMap<>();
        Map<Long, Integer> conductedByLoad = new HashMap<>();

        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (GenerationGrid.dayIndex(d.getDayOfWeek()) < 0) continue;
            if (AcademicYearUtil.isVacation(d)) continue;
            int dateSemester = AcademicYearUtil.semesterOfDate(d);
            int week = lessonInstanceService.computeAcademicWeek(d, academicYear);

            for (Map.Entry<Long, List<Schedule>> entry : schedulesByLoad.entrySet()) {
                for (Schedule s : entry.getValue()) {
                    if (s.getDayOfWeek() != d.getDayOfWeek()) continue;
                    if (s.getSemester() != null && !s.getSemester().equals(dateSemester)) continue;
                    if (s.getAcademicWeek() != null && !s.getAcademicWeek().equals(week)) continue;
                    plannedByLoad.merge(entry.getKey(), 2, Integer::sum); // академ. часов за пару
                }
            }

            if (!d.isAfter(today)) {
                List<LessonInstance> instances = lessonInstanceRepository.findByLessonDateBetween(d, d);
                for (LessonInstance li : instances) {
                    if (li.getStatus() != LessonInstance.Status.CONFIRMED
                            && li.getStatus() != LessonInstance.Status.REPLACED) {
                        continue;
                    }
                    if (li.getTeacherLoad() == null) continue;
                    int hours = li.getDurationHours() == null ? 0 : li.getDurationHours();
                    conductedByLoad.merge(li.getTeacherLoad().getId(), hours, Integer::sum);
                }
            }
        }

        List<PeriodHoursSummary> result = new ArrayList<>();
        for (TeacherLoad load : loads) {
            int planned = plannedByLoad.getOrDefault(load.getId(), 0);
            if (planned == 0 && !conductedByLoad.containsKey(load.getId())) continue; // нечего показывать
            int conducted = conductedByLoad.getOrDefault(load.getId(), 0);
            result.add(PeriodHoursSummary.builder()
                    .loadId(load.getId())
                    .teacherName(load.getTeacher() != null ? load.getTeacher().getFullName() : "Не назначен")
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

    /**
     * @param academicYear год (см. AcademicYearUtil) — если null, берётся текущий
     * @param semester     1 или 2 — если null, берётся текущий по дате (см. AcademicYearUtil.getCurrentSemester)
     * @param teacherId    ограничить одним преподавателем (для его личного кабинета) — null = все (только для админа)
     */
    public List<LoadHoursSummary> getSummary(Integer academicYear, Integer semester, Long teacherId) {
        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        int sem = semester != null ? semester : AcademicYearUtil.getCurrentSemester();

        LocalDate semesterStart = AcademicYearUtil.semesterStart(sem, year);
        LocalDate today = LocalDate.now();
        // Если семестр ещё не начался (готовим расписание заранее) — проведённых часов пока
        // нет; если уже закончился — считаем "по сегодня", но не дальше конца семестра.
        LocalDate countTo = today.isBefore(semesterStart) ? semesterStart.minusDays(1)
                : today.isAfter(AcademicYearUtil.semesterEnd(sem, year)) ? AcademicYearUtil.semesterEnd(sem, year)
                : today;

        List<TeacherLoad> loads = teacherId != null
                ? loadRepository.findByTeacherIdAndAcademicYear(teacherId, year)
                : loadRepository.findByAcademicYear(year);

        Map<Long, Integer> conductedByLoad = new HashMap<>();
        if (!countTo.isBefore(semesterStart)) {
            List<LessonInstance> instances = lessonInstanceRepository.findByLessonDateBetween(semesterStart, countTo);
            for (LessonInstance li : instances) {
                if (li.getStatus() != LessonInstance.Status.CONFIRMED
                        && li.getStatus() != LessonInstance.Status.REPLACED) {
                    continue;
                }
                if (li.getTeacherLoad() == null) continue;
                int hours = li.getDurationHours() == null ? 0 : li.getDurationHours();
                conductedByLoad.merge(li.getTeacherLoad().getId(), hours, Integer::sum);
            }
        }

        List<LoadHoursSummary> result = new ArrayList<>();
        for (TeacherLoad load : loads) {
            int planned = sem == 2
                    ? (load.getSecondSemesterHours() == null ? 0 : load.getSecondSemesterHours())
                    : (load.getFirstSemesterHours() == null ? 0 : load.getFirstSemesterHours());
            int conducted = conductedByLoad.getOrDefault(load.getId(), 0);
            result.add(LoadHoursSummary.builder()
                    .loadId(load.getId())
                    .teacherName(load.getTeacher() != null ? load.getTeacher().getFullName() : "Не назначен")
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
}
