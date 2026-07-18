package com.karyakina.schedule.service;

import com.karyakina.schedule.dto.ImportReportDto;
import com.karyakina.schedule.dto.ImportRowErrorDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * МОДУЛЬ ИМПОРТА ДАННЫХ (Excel / CSV).
 *
 * Администратор загружает файл произвольной структуры с нагрузкой преподавателей.
 * Сервис сам находит строку заголовков и распознаёт нужные колонки по синонимам
 * (жёстко заданных номеров/названий колонок не требуется), затем:
 *  1) валидирует каждую строку (пустые обязательные поля, отрицательные часы, дубликаты)
 *     и формирует отчёт об импорте;
 *  2) в рамках ОДНОЙ транзакции создаёт/обновляет сущности Teacher, Discipline,
 *     StudyGroup и их плановую нагрузку (TeacherLoad) для всех валидных строк.
 * Строки с ошибками не блокируют импорт остальных — они просто исключаются
 * и перечисляются в отчёте с номером строки.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportService {

    private final ImportPersistenceService persistenceService;

    // ---- синонимы заголовков колонок (нормализованные: нижний регистр, без "ё") ----
    private static final Map<Field, List<String>> COLUMN_SYNONYMS = new EnumMap<>(Field.class);
    static {
        COLUMN_SYNONYMS.put(Field.TEACHER, List.of(
                "фио преподавателя", "преподаватель", "фио", "педагог", "фио педагога"));
        COLUMN_SYNONYMS.put(Field.DISCIPLINE, List.of(
                "дисциплина", "предмет", "название дисциплины"));
        COLUMN_SYNONYMS.put(Field.GROUP, List.of(
                "группа", "учебная группа", "группы", "название группы"));
        COLUMN_SYNONYMS.put(Field.TOTAL_HOURS, List.of(
                "всего часов за год", "часов за год", "годовых часов", "всего часов",
                "часов в год", "плановые часы", "план часов", "план"));
        COLUMN_SYNONYMS.put(Field.HOURS_1SEM, List.of(
                "часов 1 семестр", "1 семестр часов", "часы 1 семестра", "1 полугодие часов"));
        COLUMN_SYNONYMS.put(Field.HOURS_2SEM, List.of(
                "часов 2 семестр", "2 семестр часов", "часы 2 семестра", "2 полугодие часов"));
        COLUMN_SYNONYMS.put(Field.HOURS_PER_WEEK, List.of(
                "часов в неделю", "в неделю", "часы в неделю", "нагрузка в неделю"));
        COLUMN_SYNONYMS.put(Field.LESSON_TYPE, List.of(
                "тип занятия", "вид занятия", "тип пары"));
        COLUMN_SYNONYMS.put(Field.PREFERRED, List.of(
                "предпочтительные дни и время", "предпочтения", "дни и время",
                "предпочтительное время", "удобное время"));
        COLUMN_SYNONYMS.put(Field.DEPARTMENT, List.of(
                "кафедра", "цикловая комиссия", "отделение"));
        COLUMN_SYNONYMS.put(Field.CONTROL_POINT, List.of(
                "форма контроля", "контроль", "аттестация", "экзамен зачет"));
    }

    private enum Field {
        TEACHER, DISCIPLINE, GROUP, TOTAL_HOURS, HOURS_1SEM, HOURS_2SEM,
        HOURS_PER_WEEK, LESSON_TYPE, PREFERRED, DEPARTMENT, CONTROL_POINT
    }

    /** Разобранная и провалидированная строка данных, готовая к импорту. */
    static class ParsedRow {
        int rowNumber;
        String teacherName;
        String disciplineName;
        String groupName;
        Integer totalHours;
        Integer hours1;
        Integer hours2;
        Integer hoursPerWeek;
        String lessonType;
        String preferredDaysTime;
        String department;
        String controlPointType;
    }

    /**
     * Точка входа: разбор файла, валидация, импорт валидных строк в одной транзакции.
     */
    public ImportReportDto importFile(MultipartFile file, Integer academicYear) {
        List<List<String>> rows;
        try {
            rows = readAllRows(file);
        } catch (Exception e) {
            log.error("Не удалось прочитать файл импорта: {}", e.getMessage(), e);
            return ImportReportDto.builder()
                    .success(false)
                    .applied(false)
                    .totalRows(0)
                    .processedRows(0)
                    .errorRows(0)
                    .errors(List.of(ImportRowErrorDto.builder()
                            .rowNumber(0)
                            .message("Не удалось прочитать файл: " + e.getMessage())
                            .build()))
                    .summary("Импорт не выполнен: файл повреждён или имеет неподдерживаемый формат")
                    .build();
        }

        if (rows.isEmpty()) {
            return ImportReportDto.builder()
                    .success(false).applied(false)
                    .totalRows(0).processedRows(0).errorRows(0)
                    .errors(List.of())
                    .summary("Файл пуст")
                    .build();
        }

        // 1. Найти строку заголовков
        int headerRowIndex = detectHeaderRow(rows);
        if (headerRowIndex < 0) {
            return ImportReportDto.builder()
                    .success(false).applied(false)
                    .totalRows(rows.size()).processedRows(0).errorRows(0)
                    .errors(List.of())
                    .summary("Не удалось распознать заголовки колонок. Убедитесь, что в файле " +
                            "есть колонки ФИО преподавателя и Дисциплина")
                    .build();
        }
        Map<Field, Integer> columnMap = detectColumns(rows.get(headerRowIndex));

        // 2. Разобрать и провалидировать строки данных
        List<ParsedRow> validRows = new ArrayList<>();
        List<ImportRowErrorDto> errors = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        int totalDataRows = 0;

        for (int i = headerRowIndex + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (isRowBlank(row)) continue;
            totalDataRows++;
            int excelRowNumber = i + 1; // 1-based, как в Excel

            try {
                ParsedRow parsed = parseRow(row, columnMap, excelRowNumber);

                if (isBlank(parsed.teacherName)) {
                    errors.add(rowError(excelRowNumber, "Не указано ФИО преподавателя", row));
                    continue;
                }
                if (isBlank(parsed.disciplineName)) {
                    errors.add(rowError(excelRowNumber, "Не указана дисциплина", row));
                    continue;
                }
                if (isBlank(parsed.groupName)) {
                    errors.add(rowError(excelRowNumber, "Не указана группа", row));
                    continue;
                }
                if (parsed.totalHours != null && parsed.totalHours < 0) {
                    errors.add(rowError(excelRowNumber, "Отрицательное значение часов за год", row));
                    continue;
                }
                if (parsed.hoursPerWeek != null && parsed.hoursPerWeek < 0) {
                    errors.add(rowError(excelRowNumber, "Отрицательное значение часов в неделю", row));
                    continue;
                }
                if (parsed.totalHours == null && parsed.hours1 == null && parsed.hours2 == null) {
                    errors.add(rowError(excelRowNumber, "Не указаны плановые часы (год или по семестрам)", row));
                    continue;
                }

                String dedupKey = normalize(parsed.teacherName) + "|" + normalize(parsed.disciplineName)
                        + "|" + normalize(parsed.groupName);
                if (!seenKeys.add(dedupKey)) {
                    errors.add(rowError(excelRowNumber,
                            "Дубликат строки (тот же преподаватель/дисциплина/группа уже встречался в файле)", row));
                    continue;
                }

                validRows.add(parsed);
            } catch (Exception e) {
                errors.add(rowError(excelRowNumber, "Ошибка обработки строки: " + e.getMessage(), row));
            }
        }

        // 3. Применить валидные строки в одной транзакции (отдельный бин, чтобы
        // @Transactional гарантированно применялся через прокси Spring)
        ImportPersistenceService.ImportResult result = persistenceService.applyRows(validRows, academicYear);

        List<String> detectedColumns = new ArrayList<>();
        columnMap.keySet().forEach(f -> detectedColumns.add(f.name()));

        String summary = String.format(
                "Успешно обработано %d записей из %d, %d ошибок%s",
                result.processedLoads, totalDataRows, errors.size(),
                errors.isEmpty() ? "" : " в строках " + errors.stream()
                        .map(e -> String.valueOf(e.getRowNumber()))
                        .reduce((a, b) -> a + ", " + b).orElse(""));

        return ImportReportDto.builder()
                .success(true)
                .applied(result.processedLoads > 0)
                .totalRows(totalDataRows)
                .processedRows(result.processedLoads)
                .errorRows(errors.size())
                .createdTeachers(result.createdTeachers)
                .createdDisciplines(result.createdDisciplines)
                .createdGroups(result.createdGroups)
                .createdLoads(result.createdLoads)
                .updatedLoads(result.updatedLoads)
                .errors(errors)
                .detectedColumns(detectedColumns)
                .summary(summary)
                .build();
    }

    // ==================== Разбор файла ====================

    private List<List<String>> readAllRows(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (filename.endsWith(".csv")) {
            return readCsv(file.getInputStream());
        }
        return readExcel(file.getInputStream());
    }

    private List<List<String>> readExcel(InputStream is) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            int lastCol = 0;
            for (Row row : sheet) {
                lastCol = Math.max(lastCol, row.getLastCellNum());
            }
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < lastCol; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private List<List<String>> readCsv(InputStream is) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            String delimiter = null;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (delimiter == null) {
                    delimiter = line.contains(";") ? ";" : ",";
                }
                String[] parts = line.split(java.util.regex.Pattern.quote(delimiter), -1);
                List<String> cells = new ArrayList<>();
                for (String p : parts) {
                    String v = p.trim();
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                        v = v.substring(1, v.length() - 1);
                    }
                    cells.add(v);
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private int detectHeaderRow(List<List<String>> rows) {
        int bestRow = -1;
        int bestScore = 0;
        int limit = Math.min(rows.size(), 10);
        for (int i = 0; i < limit; i++) {
            Map<Field, Integer> map = detectColumns(rows.get(i));
            int score = map.size();
            if (score > bestScore) {
                bestScore = score;
                bestRow = i;
            }
        }
        // Требуем минимум ФИО + дисциплину, иначе не считаем это заголовком
        if (bestRow >= 0) {
            Map<Field, Integer> map = detectColumns(rows.get(bestRow));
            if (!map.containsKey(Field.TEACHER) || !map.containsKey(Field.DISCIPLINE)) {
                return -1;
            }
        }
        return bestRow;
    }

    private Map<Field, Integer> detectColumns(List<String> headerRow) {
        Map<Field, Integer> result = new EnumMap<>(Field.class);
        for (int col = 0; col < headerRow.size(); col++) {
            String cell = normalize(headerRow.get(col));
            if (cell.isEmpty()) continue;
            for (Map.Entry<Field, List<String>> entry : COLUMN_SYNONYMS.entrySet()) {
                if (result.containsKey(entry.getKey())) continue; // первое совпадение побеждает
                for (String synonym : entry.getValue()) {
                    if (cell.contains(synonym)) {
                        result.put(entry.getKey(), col);
                        break;
                    }
                }
            }
        }
        return result;
    }

    private ParsedRow parseRow(List<String> row, Map<Field, Integer> columnMap, int rowNumber) {
        ParsedRow parsed = new ParsedRow();
        parsed.rowNumber = rowNumber;
        parsed.teacherName = getCell(row, columnMap, Field.TEACHER);
        parsed.disciplineName = getCell(row, columnMap, Field.DISCIPLINE);
        parsed.groupName = getCell(row, columnMap, Field.GROUP);
        parsed.totalHours = parseInt(getCell(row, columnMap, Field.TOTAL_HOURS));
        parsed.hours1 = parseInt(getCell(row, columnMap, Field.HOURS_1SEM));
        parsed.hours2 = parseInt(getCell(row, columnMap, Field.HOURS_2SEM));
        parsed.hoursPerWeek = parseInt(getCell(row, columnMap, Field.HOURS_PER_WEEK));
        parsed.lessonType = emptyToNull(getCell(row, columnMap, Field.LESSON_TYPE));
        parsed.preferredDaysTime = emptyToNull(getCell(row, columnMap, Field.PREFERRED));
        parsed.department = emptyToNull(getCell(row, columnMap, Field.DEPARTMENT));
        parsed.controlPointType = emptyToNull(getCell(row, columnMap, Field.CONTROL_POINT));
        return parsed;
    }

    private String getCell(List<String> row, Map<Field, Integer> columnMap, Field field) {
        Integer idx = columnMap.get(field);
        if (idx == null || idx >= row.size()) return null;
        String v = row.get(idx);
        return v == null ? null : v.trim();
    }

    private Integer parseInt(String v) {
        if (isBlank(v)) return null;
        String digits = v.replaceAll("[^0-9-]", "");
        if (digits.isEmpty() || digits.equals("-")) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isRowBlank(List<String> row) {
        return row.stream().allMatch(this::isBlank);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String emptyToNull(String s) {
        return isBlank(s) ? null : s;
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').trim().replaceAll("\\s+", " ");
    }

    private ImportRowErrorDto rowError(int rowNumber, String message, List<String> row) {
        String raw = String.join(" | ", row.subList(0, Math.min(row.size(), 6)));
        if (raw.length() > 200) raw = raw.substring(0, 200) + "...";
        return ImportRowErrorDto.builder()
                .rowNumber(rowNumber)
                .message(message)
                .rawData(raw)
                .build();
    }
}
