package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class ProductivityDto {
    private Long teacherId;
    private String teacherName;
    private double productivityIndex;
    private String color; // success / warning / danger
    private double planCompletionPercent;
    private double timelinessPercent;
    private String formulaUsed;
}
