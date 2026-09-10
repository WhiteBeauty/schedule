package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.dto.MonthLessonDto;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Расписание на месяц: "раскрывает" еженедельные шаблоны {@link Schedule} на реальные
 * даты выбранного календарного месяца и накладывает на них фактический статус из
 * {@link LessonInstance} — если занятие на конкретную дату уже материализовано
 * (например, при нём была оформлена замена преподавателя, отмена и т.п.).
 *
 * Занятия НЕ материализуются автоматически при просмотре (в отличие от
 * {@code LessonInstanceService.generateInstancesForDate}) — это чисто read-only
 * представление: если для даты ещё нет LessonInstance, пара показывается как
 * запланированная по шаблону с исходным преподавателем.
 */
@Service
@RequiredArgsConstructor
public class MonthScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final LessonInstanceRepository instanceRepository;
    private final LessonInstanceService lessonInstanceService;

    public List<MonthLessonDto> getMonth(int calendarYear, int calendarMonth, Integer academicYear,
                                          Long teacherId, Long groupId) {
        LocalDate from = LocalDate.of(calendarYear, calendarMonth, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());

        int resolvedAcademicYear = academicYear != null
                ? academicYear
                : (from.getMonthValue() >= 9 ? from.getYear() : from.getYear() - 1);

        List<Schedule> schedules = scheduleRepository.findByAcademicYear(resolvedAcademicYear);
        if (teacherId != null) {
            schedules = schedules.stream()
                    .filter(s -> s.getTeacherLoad().getTeacher().getId().equals(teacherId))
                    .collect(Collectors.toList());
        }
        if (groupId != null) {
            schedules = schedules.stream()
                    .filter(s -> s.getTeacherLoad().getGroup().getId().equals(groupId))
                    .collect(Collectors.toList());
        }

        // Уже материализованные занятия за диапазон дат месяца — быстрый lookup по (scheduleId, дата).
        Map<String, LessonInstance> instanceByKey = instanceRepository.findByLessonDateBetween(from, to).stream()
                .filter(li -> li.getSchedule() != null)
                .collect(Collectors.toMap(
                        li -> li.getSchedule().getId() + "_" + li.getLessonDate(),
                        li -> li,
                        (a, b) -> a));

        List<MonthLessonDto> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            // ТЗ п.1: расписание строго привязано к семестру. Без этой проверки шаблон
            // (Schedule без конкретной даты — только день недели) "бесконечно" проецировался
            // бы вперёд в календаре: на зимние каникулы (31 дек — 13 янв) и даже в СЛЕДУЮЩИЙ
            // семестр, который ещё не сгенерирован. isVacation блокирует каникулы целиком;
            // сравнение семестра ниже блокирует случай "это пара семестра 1, а дата уже
            // в семестре 2" (и наоборот) — даже если оба в пределах одного academicYear.
            if (AcademicYearUtil.isVacation(date)) continue;
            int dateSemester = AcademicYearUtil.semesterOfDate(date);

            int week = lessonInstanceService.computeAcademicWeek(date, resolvedAcademicYear);
            for (Schedule s : schedules) {
                if (s.getDayOfWeek() != date.getDayOfWeek()) continue;
                // semester == null — записи, созданные до появления этого поля (легаси);
                // показываем их как раньше, без фильтрации по семестру, ради совместимости.
                if (s.getSemester() != null && !s.getSemester().equals(dateSemester)) continue;
                // academicWeek = null означает "каждую неделю" (подавляющее большинство пар —
                // и вручную заведённых, и сгенерированных автосоставлением). Непустое значение
                // бывает только у одноразовых подменных записей, которые создаёт
                // SickLeaveReschedulingService на время больничного — они должны показываться
                // только на свою неделю. Раньше здесь стоял тот же фильтр, но он ошибочно
                // выкидывал из календаря целые дни недели (см. computeAcademicWeek) — теперь,
                // когда номер недели считается детерминированно (WeekFields.ISO, а не локаль
                // сервера), фильтрация снова безопасна.
                if (s.getAcademicWeek() != null && !s.getAcademicWeek().equals(week)) continue;

                LessonInstance instance = instanceByKey.get(s.getId() + "_" + date);
                String originalTeacherName = s.getTeacherLoad().getTeacher().getFullName();
                String status = instance != null ? instance.getStatus().name() : "PLANNED";
                boolean substituted = instance != null && instance.getStatus() == LessonInstance.Status.REPLACED;
                String actualTeacherName = (instance != null && instance.getActualTeacher() != null)
                        ? instance.getActualTeacher().getFullName()
                        : originalTeacherName;

                result.add(new MonthLessonDto(
                        s.getId(),
                        instance != null ? instance.getId() : null,
                        date,
                        s.getDayOfWeek().toString(),
                        s.getStartTime().toString(),
                        s.getEndTime().toString(),
                        s.getClassroom(),
                        s.getTeacherLoad().getDiscipline().getName(),
                        s.getTeacherLoad().getGroup().getName(),
                        s.getTeacherLoad().getTeacher().getId(),
                        originalTeacherName,
                        actualTeacherName,
                        status,
                        substituted,
                        instance != null ? instance.getNote() : null));
            }
        }

        result.sort(Comparator.comparing(MonthLessonDto::date).thenComparing(MonthLessonDto::startTime));
        return result;
    }
}
