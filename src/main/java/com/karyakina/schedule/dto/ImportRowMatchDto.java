package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class ImportRowMatchDto {
    private int rowIndex;
    private int excelRowNumber;
    private String teacherNameFromFile;
    private String disciplineName;
    private String groupName;
    private Integer totalHours;

    private String matchStatus;
    private Long candidateTeacherId;
    private String candidateTeacherName;
    private String candidateDepartment;
    private double similarity;
}
