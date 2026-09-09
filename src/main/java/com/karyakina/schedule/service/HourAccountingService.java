package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
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
