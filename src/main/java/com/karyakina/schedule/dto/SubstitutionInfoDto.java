package com.karyakina.schedule.dto;

import java.time.LocalDate;

public record SubstitutionInfoDto(
        Long scheduleId,
        LocalDate lessonDate,
        String originalTeacherName,
        String actualTeacherName
) {
}
