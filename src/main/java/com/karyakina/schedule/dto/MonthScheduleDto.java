package com.karyakina.schedule.dto;

import java.util.List;

public record MonthScheduleDto(
        List<MonthLessonDto> lessons,
        List<MonthEventDto> events
) {
}
