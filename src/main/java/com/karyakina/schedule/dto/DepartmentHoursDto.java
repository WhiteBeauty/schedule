package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DepartmentHoursDto {
    private String department;
    private int plannedHours;
    private int conductedHours;
    private double completionPercent;
}
