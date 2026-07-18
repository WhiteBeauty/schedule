package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class ImportRowErrorDto {
    private int rowNumber;   // номер строки в файле (1-based, с учётом заголовка)
    private String message;  // описание ошибки
    private String rawData;  // краткое содержимое строки для контекста
}
