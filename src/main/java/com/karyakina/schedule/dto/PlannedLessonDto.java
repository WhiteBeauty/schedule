package com.karyakina.schedule.dto;

import com.karyakina.schedule.domain.Schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * Одна пара из результата автосоставления — плоская, без ленивых JPA-прокси.
 * Сущность {@link Schedule} специально не отдаётся наружу: сериализация ленивых связей
 * при частично собранном черновике — источник «пустых» ошибок на фронтенде.
 *
 * @param scheduleId id записи расписания; {@code null}, пока черновик не сохранён
 */
public record PlannedLessonDto(
        Long scheduleId,
        Long teacherLoadId,
        Long groupId,
        String groupName,
        Long disciplineId,
        String disciplineName,
        Long teacherId,
        String teacherName,
        String classroom,
        DayOfWeek dayOfWeek,
        String dayName,
        int pairNumber,
        LocalTime startTime,
        LocalTime endTime
) {

    public static PlannedLessonDto from(Schedule schedule, int pairNumber, String dayName) {
        var load = schedule.getTeacherLoad();
        var teacher = load == null ? null : load.getTeacher();
        var group = load == null ? null : load.getGroup();
        var discipline = load == null ? null : load.getDiscipline();
        return new PlannedLessonDto(
                schedule.getId(),
                load == null ? null : load.getId(),
                group == null ? null : group.getId(),
                group == null ? "—" : group.getName(),
                discipline == null ? null : discipline.getId(),
                discipline == null ? "—" : discipline.getName(),
                teacher == null ? null : teacher.getId(),
                teacher == null ? "не назначен" : teacher.getFullName(),
                schedule.getClassroom(),
                schedule.getDayOfWeek(),
                dayName,
                pairNumber,
                schedule.getStartTime(),
                schedule.getEndTime());
    }
}
