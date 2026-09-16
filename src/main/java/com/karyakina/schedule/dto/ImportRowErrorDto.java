package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class ImportRowErrorDto {
    private int rowNumber;
    private String message;
    private String rawData;
}
