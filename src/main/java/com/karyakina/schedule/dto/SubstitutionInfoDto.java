package com.karyakina.schedule.dto;

import java.time.LocalDate;

/**
 * Актуальная замена преподавателя на конкретную дату — используется фронтендом,
 * чтобы подсветить пару в расписании другим цветом и показать, кто сейчас
 * фактически её ведёт вместо исходного преподавателя.
 */
public record SubstitutionInfoDto(
        Long scheduleId,
        LocalDate lessonDate,
        String originalTeacherName,
        String actualTeacherName
) {
}
