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
    // Не ошибки — строки, где несколько групп/дисциплин в одной ячейке были разбиты
    // по запятой/`;`, но программа не до конца уверена, что разбиение верное
    // (см. ImportService.expandMultiValueRows). Импорт всё равно применяется —
    // это просто явный сигнал администратору перепроверить конкретные строки.
    private List<ImportRowErrorDto> splitNotices;
    private List<String> detectedColumns; // какие колонки распознала система
    private String summary;            // человекочитаемое резюме для отображения администратору
}
