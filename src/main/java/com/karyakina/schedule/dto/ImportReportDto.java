package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Отчёт об импорте файла нагрузки (Excel/CSV) — результат работы ImportService.
 * Формат сообщения аналогичен описанному в ТЗ:
 * "Успешно обработано 150 записей, 2 ошибки в строках 15 и 42".
 */
@Data @Builder
public class ImportReportDto {
    private boolean success;           // true, если импорт применён (0 ошибок или частичный импорт разрешён)
    private boolean applied;           // были ли изменения реально сохранены в БД
    private int totalRows;             // всего строк с данными в файле
    private int processedRows;         // строк, успешно провалидированных и импортированных
    private int errorRows;             // строк с ошибками
    private int createdTeachers;
    private int createdDisciplines;
    private int createdGroups;
    private int createdLoads;
    private int updatedLoads;
    private List<ImportRowErrorDto> errors;
    private List<String> detectedColumns; // какие колонки распознала система
    private String summary;            // человекочитаемое резюме для отображения администратору
}
