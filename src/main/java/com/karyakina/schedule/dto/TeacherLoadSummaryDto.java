package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TeacherLoadSummaryDto {
    private Long teacherId;
    private String teacherName;
    private String department;
    private int plannedHours;
    private int conductedHours;
    private int remainingHours;
    private double curatorshipHours;
    private double ratePercent;
    private String status;
}
