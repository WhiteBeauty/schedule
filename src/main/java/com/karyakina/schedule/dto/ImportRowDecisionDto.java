package com.karyakina.schedule.dto;

import lombok.Data;

@Data
public class ImportRowDecisionDto {
    private int rowIndex;
    private String action;
    private Long teacherId;

    private String department;
    private String position;
    private String phone;
    private String preferredDays;
}
