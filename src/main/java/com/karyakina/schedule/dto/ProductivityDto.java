package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class ProductivityDto {
    private Long teacherId;
    private String teacherName;
    private double productivityIndex;
    private String color;
    private String level;
    private double planCompletionPercent;
    private double timelinessPercent;
    private double accuracyPercent;
    private double targetProgress;
    private double curatorshipPlannedHours;
    private double curatorshipDoneHours;
    private String formulaUsed;
}
