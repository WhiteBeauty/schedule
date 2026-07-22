package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data @Builder
public class ImportPreviewDto {
    private boolean success;
    private int totalRows;
    private int exactCount;
    private int fuzzyCount;
    private int newCount;
    private int errorCount;
    private List<ImportRowMatchDto> rows;
    private List<ImportRowErrorDto> errors;
    private List<String> detectedColumns;
    private String summary;
}
