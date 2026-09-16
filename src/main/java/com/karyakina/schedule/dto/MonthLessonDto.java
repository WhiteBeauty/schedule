package com.karyakina.schedule.dto;

import java.time.LocalDate;

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
