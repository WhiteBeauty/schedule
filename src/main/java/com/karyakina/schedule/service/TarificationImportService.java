package com.karyakina.schedule.service;

import com.karyakina.schedule.dto.ImportReportDto;
import com.karyakina.schedule.dto.ImportRowErrorDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * МОДУЛЬ ИМПОРТА ФАЙЛА "ТАРИФИКАЦИЯ" (годовая сводная таблица нагрузки).
 *
 * В отличие от {@link ImportService} (плоская таблица "одна строка = преподаватель +
 * дисциплина + группа"), файл тарификации — это сводная (pivot) таблица на листе
 * "Часовка":
 *  - строки сгруппированы по преподавателю (ФИО указано один раз в первой строке его
 *    блока), внутри — по дисциплине (тоже одна строка на дисциплину с маркером "ч" —
 *    часы; следующие 2 строки "д"/"к" зарезервированы в самом файле под другие виды
 *    нагрузки, но фактически всегда пустые, поэтому они пропускаются);
 *  - столбцы после "Предмет"/"Часы / Конс." разбиты на блоки по 4 колонки — один блок
 *    на учебную группу (название группы и курс — в объединённых по всему блоку
 *    ячейках), внутри блока: часы I семестра, форма контроля I семестра, часы
 *    II семестра, форма контроля II семестра (ДЗ/З/Э/РК).
 *
 * Результат разбора — плоский список {@link TarificationRow}, который дальше
 * применяется в {@link ImportPersistenceService#applyTarificationRows}: для каждой
 * непустой пары (дисциплина, группа) у преподавателя создаётся/обновляется
 * {@code TeacherLoad} с часами по семестрам и формами контроля. Сама раскладка по
 * дням недели и месяцу в файле не содержится (это годовые часы, а не расписание) —
 * после импорта администратор запускает уже существующий модуль автосоставления
 * расписания (ScheduleGeneratorService), который считает часы в неделю от плановых
 * часов и расставляет пары по дням.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TarificationImportService {

    private final ImportPersistenceService persistenceService;

    private static final int GROUP_BLOCK_WIDTH = 4; // часы1, контроль1, часы2, контроль2
    private static final Pattern LEADING_NUMBER = Pattern.compile("(\\d+)");

    /** Одна "ученическая" запись: преподаватель ведёт дисциплину у конкретной группы. */
    public static class TarificationRow {
        public String teacherName;
        public String disciplineName;
        public String groupName;
        public String specialty;
        public Integer course;
        public Integer studentCount;
        public Integer hours1;
        public Integer hours2;
        public String control1;
        public String control2;
    }

    public static class ParseResult {
        public List<TarificationRow> rows = new ArrayList<>();
        public List<ImportRowErrorDto> warnings = new ArrayList<>();
        public String sheetName;
        public String fatalError;
    }

    /** Точка входа: разбор файла и сохранение всех валидных записей в одной транзакции. */
    public ImportReportDto importFile(MultipartFile file, Integer academicYear) {
        ParseResult parsed = parse(file);
        if (parsed.fatalError != null) {
            return ImportReportDto.builder()
                    .success(false)
                    .applied(false)
                    .totalRows(0)
                    .processedRows(0)
                    .errorRows(0)
                    .errors(List.of(ImportRowErrorDto.builder().rowNumber(0).message(parsed.fatalError).build()))
                    .summary(parsed.fatalError)
                    .build();
        }

        ImportPersistenceService.ImportResult result =
                persistenceService.applyTarificationRows(parsed.rows, academicYear);

        String summary = String.format(Locale.ROOT,
                "Обработано записей: %d. Создано: преподавателей — %d, дисциплин — %d, групп — %d, "
                        + "нагрузок — %d, обновлено нагрузок — %d.%s",
                parsed.rows.size(), result.createdTeachers, result.createdDisciplines, result.createdGroups,
                result.createdLoads, result.updatedLoads,
                parsed.warnings.isEmpty() ? "" : " Предупреждений: " + parsed.warnings.size() + ".");

        return ImportReportDto.builder()
                .success(true)
                .applied(true)
                .totalRows(parsed.rows.size())
                .processedRows(result.processedLoads)
                .errorRows(0)
                .createdTeachers(result.createdTeachers)
                .createdDisciplines(result.createdDisciplines)
                .createdGroups(result.createdGroups)
                .createdLoads(result.createdLoads)
                .updatedLoads(result.updatedLoads)
                .errors(List.of())
                .splitNotices(parsed.warnings)
                .detectedColumns(List.of("Преподаватель", "Предмет", "Группы (по блокам колонок)", "Семестр I/II"))
                .summary(summary)
                .build();
    }

    // ------------------------------------------------------------------------------
    // Разбор файла
    // ------------------------------------------------------------------------------

    public ParseResult parse(MultipartFile file) {
        ParseResult result = new ParseResult();
        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = findTarificationSheet(workbook);
            if (sheet == null) {
                result.fatalError = "Не найден лист с таблицей тарификации "
                        + "(должна быть колонка \"Преподаватель\" и рядом \"Предмет\").";
                return result;
            }
            result.sheetName = sheet.getSheetName();
            parseSheet(sheet, result);
        } catch (Exception e) {
            log.error("Ошибка разбора файла тарификации", e);
            result.fatalError = "Не удалось прочитать файл: " + e.getMessage();
        }
        return result;
    }

    private Sheet findTarificationSheet(Workbook workbook) {
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            if (findHeaderRow(sheet) >= 0) {
                return sheet;
            }
        }
        return null;
    }

    /** Ищет строку с заголовком "Преподаватель" в первых 10 строках листа. Возвращает 0-based номер строки или -1. */
    private int findHeaderRow(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        int maxRow = Math.min(sheet.getLastRowNum(), 10);
        for (int r = 0; r <= maxRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < Math.min(row.getLastCellNum(), 6); c++) {
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell == null) continue;
                String text = normalize(formatter.formatCellValue(cell));
                if (text.contains("преподаватель")) {
                    return r;
                }
            }
        }
        return -1;
    }

    private void parseSheet(Sheet sheet, ParseResult result) {
        DataFormatter formatter = new DataFormatter();
        int headerRow = findHeaderRow(sheet);
        if (headerRow < 0) {
            result.fatalError = "Не найдена строка заголовков.";
            return;
        }

        int lastCol = 0;
        for (Row row : sheet) if (row != null) lastCol = Math.max(lastCol, row.getLastCellNum());

        // Строку целиком читаем как текст — это устойчиво к объединённым ячейкам
        // (у них значение есть только в левой верхней ячейке блока, остальные — "").
        List<String> headerCells = readRowAsText(sheet, headerRow, lastCol, formatter);

        int teacherCol = indexOfContaining(headerCells, "преподаватель");
        int disciplineCol = indexOfContaining(headerCells, "предмет");
        if (disciplineCol < 0) disciplineCol = indexOfContaining(headerCells, "дисциплин");
        int markerCol = -1;
        for (int c = disciplineCol + 1; c < Math.min(disciplineCol + 3, headerCells.size()); c++) {
            if (normalize(headerCells.get(c)).contains("час")) { markerCol = c; break; }
        }
        int totalPerSubjectCol = indexOfContaining(headerCells, "всего по предмету");

        if (teacherCol < 0 || disciplineCol < 0 || markerCol < 0 || totalPerSubjectCol < 0) {
            result.fatalError = "Не удалось распознать структуру заголовков листа \"" + sheet.getSheetName()
                    + "\" (ожидались колонки \"Преподаватель\", \"Предмет\", \"Часы\" и \"Всего по предмету\").";
            return;
        }

        int groupNameRowIdx = headerRow + 1;
        int courseRowIdx = headerRow + 2;
        int studentsRowIdx = headerRow + 3;
        int dataStartRow = headerRow + 5;

        List<String> groupNameRow = readRowAsText(sheet, groupNameRowIdx, lastCol, formatter);
        List<String> courseRow = readRowAsText(sheet, courseRowIdx, lastCol, formatter);
        List<String> studentsRow = readRowAsText(sheet, studentsRowIdx, lastCol, formatter);

        // Начало области групп — первая непустая ячейка в строке названий групп
        // правее маркерной колонки "ч/д/к".
        int groupsStartCol = -1;
        for (int c = markerCol + 1; c < totalPerSubjectCol; c++) {
            if (!groupNameRow.get(c).isBlank()) { groupsStartCol = c; break; }
        }
        if (groupsStartCol < 0) {
            result.fatalError = "Не найдены колонки учебных групп на листе \"" + sheet.getSheetName() + "\".";
            return;
        }

        List<GroupBlock> blocks = new ArrayList<>();
        int col = groupsStartCol;
        while (col < totalPerSubjectCol) {
            String label = groupNameRow.get(col).trim();
            if (label.isEmpty()) { col++; continue; }
            int blockEnd = col + 1;
            while (blockEnd < totalPerSubjectCol && groupNameRow.get(blockEnd).isBlank()) blockEnd++;

            GroupBlock block = new GroupBlock();
            block.startCol = col;
            block.label = label;
            block.course = parseLeadingInt(courseRow.get(col));
            block.studentCount = parseNumber(studentsRow.get(col));
            blocks.add(block);
            col = blockEnd;
        }

        String currentTeacher = null;
        int lastRow = sheet.getLastRowNum();
        for (int r = dataStartRow; r <= lastRow; r++) {
            List<String> rowCells = readRowAsText(sheet, r, lastCol, formatter);
            if (rowCells.stream().allMatch(String::isBlank)) continue;

            String teacherCell = rowCells.get(teacherCol).trim();
            if (!teacherCell.isEmpty()) {
                currentTeacher = teacherCell;
            }

            String marker = normalize(rowCells.get(markerCol));
            if (!marker.equals("ч")) continue; // "д"/"к" в этом файле всегда пустые — пропускаем

            String discipline = rowCells.get(disciplineCol).trim();
            if (discipline.isEmpty()) continue;
            if (currentTeacher == null) {
                result.warnings.add(ImportRowErrorDto.builder()
                        .rowNumber(r + 1)
                        .message("Строка с дисциплиной \"" + discipline + "\" без преподавателя — пропущена")
                        .rawData(discipline)
                        .build());
                continue;
            }

            for (GroupBlock block : blocks) {
                Integer hours1 = parseNumber(rowCells.get(block.startCol));
                String control1 = emptyToNull(rowCells.get(block.startCol + 1));
                Integer hours2 = parseNumber(rowCells.get(block.startCol + 2));
                String control2 = emptyToNull(rowCells.get(block.startCol + 3));

                if (nz(hours1) == 0 && nz(hours2) == 0) continue; // эта группа не учится у этого преподавателя

                TarificationRow tr = new TarificationRow();
                tr.teacherName = currentTeacher;
                tr.disciplineName = discipline;
                tr.groupName = block.label;
                tr.specialty = stripGroupNumberPrefix(block.label);
                tr.course = block.course;
                tr.studentCount = block.studentCount;
                tr.hours1 = hours1;
                tr.hours2 = hours2;
                tr.control1 = control1;
                tr.control2 = control2;
                result.rows.add(tr);
            }
        }
    }

    private static class GroupBlock {
        int startCol;
        String label;
        Integer course;
        Integer studentCount;
    }

    // ------------------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------------------

    private List<String> readRowAsText(Sheet sheet, int rowIdx, int lastCol, DataFormatter formatter) {
        List<String> cells = new ArrayList<>();
        Row row = sheet.getRow(rowIdx);
        for (int c = 0; c < lastCol; c++) {
            if (row == null) { cells.add(""); continue; }
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        return cells;
    }

    private int indexOfContaining(List<String> cells, String needleLower) {
        for (int i = 0; i < cells.size(); i++) {
            if (normalize(cells.get(i)).contains(needleLower)) return i;
        }
        return -1;
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Integer parseNumber(String s) {
        if (s == null || s.isBlank()) return null;
        String cleaned = s.trim().replace(',', '.').replace(" ", "");
        try {
            return (int) Math.round(Double.parseDouble(cleaned));
        } catch (NumberFormatException e) {
            return null; // декоративные символы шрифта-иконки и т.п. — не число
        }
    }

    private Integer parseLeadingInt(String s) {
        if (s == null) return null;
        Matcher m = LEADING_NUMBER.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** "№ 4 Механизаторы" -> "Механизаторы" (для поля specialty новой группы). */
    private String stripGroupNumberPrefix(String label) {
        return label.replaceFirst("^№\\s*\\d+\\s*", "").trim();
    }
}
