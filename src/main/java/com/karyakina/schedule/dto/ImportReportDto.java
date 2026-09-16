package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data @Builder
public class ImportReportDto {
    private boolean success;
    private boolean applied;
    private int totalRows;
    private int processedRows;
    private int errorRows;
    private int createdTeachers;
    private int createdDisciplines;
    private int createdGroups;
    private int createdLoads;
    private int updatedLoads;
    private int createdClassrooms;
    private List<ImportRowErrorDto> errors;
    private List<ImportRowErrorDto> splitNotices;
    private List<String> detectedColumns;
    private String summary;
}
