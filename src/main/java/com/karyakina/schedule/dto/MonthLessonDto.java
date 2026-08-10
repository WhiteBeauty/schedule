package com.karyakina.schedule.dto;

import java.time.LocalDate;

/**
 * Одно занятие на конкретную календарную дату для месячного вида расписания
 * ({@code /schedule/month}). Строится путём "раскрытия" шаблона {@code Schedule}
 * на реальные даты месяца и наложения фактического статуса из {@code LessonInstance},
 * если занятие уже материализовано (см. {@code MonthScheduleService}).
 */
public record MonthLessonDto(
        Long scheduleId,
        Long instanceId,
        LocalDate date,
        String dayOfWeek,
        String startTime,
        String endTime,
        String classroom,
        String disciplineName,
        String groupName,
        Long teacherId,
        String originalTeacherName,
        String actualTeacherName,
        String status,
        boolean substituted,
        String note
) {
}
