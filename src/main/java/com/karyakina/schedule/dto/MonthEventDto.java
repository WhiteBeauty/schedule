package com.karyakina.schedule.dto;

import java.time.LocalDate;

public record MonthEventDto(
        Long id,
        String type,
        String typeLabel,
        Long groupId,
        String groupName,
        String disciplineName,
        LocalDate startDate,
        LocalDate endDate
) {
}
