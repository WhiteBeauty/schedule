package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.dto.ImportPreviewDto;
import com.karyakina.schedule.dto.ImportReportDto;
import com.karyakina.schedule.dto.ImportRowDecisionDto;
import com.karyakina.schedule.dto.ImportRowErrorDto;
import com.karyakina.schedule.dto.ImportRowMatchDto;
import com.karyakina.schedule.repository.DisciplineRepository;
import com.karyakina.schedule.repository.StudyGroupRepository;
import com.karyakina.schedule.repository.TeacherRepository;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportService {

    private final ImportPersistenceService persistenceService;
    private final TeacherRepository teacherRepository;
    private final DisciplineRepository disciplineRepository;
    private final StudyGroupRepository studyGroupRepository;

    private static final Map<Field, List<String>> COLUMN_SYNONYMS = new EnumMap<>(Field.class);
    static {
        COLUMN_SYNONYMS.put(Field.TEACHER, List.of(
                "фио преподавателя", "преподаватель", "фио", "педагог", "фио педагога"));
        COLUMN_SYNONYMS.put(Field.CANDIDATE_TEACHERS, List.of(
                "возможные преподаватели", "варианты преподавателя", "кандидаты в преподаватели",
                "кандидаты", "варианты преподавателей"));
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
        TEACHER, CANDIDATE_TEACHERS, DISCIPLINE, GROUP, TOTAL_HOURS, HOURS_1SEM, HOURS_2SEM,
        HOURS_PER_WEEK, LESSON_TYPE, PREFERRED, DEPARTMENT, CONTROL_POINT
    }

    static class ParsedRow {
        int rowNumber;
        String teacherName;
        String candidateTeachers;
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

        boolean splitAmbiguous;
        String splitNote;
    }

    public ImportReportDto importFile(MultipartFile file, Integer academicYear) {
        ParseResult parsed = parseAndValidate(file);
        if (parsed.fatalError != null) {
            return parsed.fatalError;
        }

        ImportPersistenceService.ImportResult result = persistenceService.applyRows(parsed.validRows, academicYear);

        Map<String, String> teacherDisciplines = parseTeacherDisciplineSheet(file);
        if (!teacherDisciplines.isEmpty()) {
            persistenceService.applyTeacherDisciplines(teacherDisciplines);
        }

        Map<String, String> groupDisciplines = parseGroupDisciplinesSheet(file);
        if (!groupDisciplines.isEmpty()) {
            result.createdGroups += persistenceService.applyGroupsSheet(groupDisciplines);
        }

        List<ParsedClassroom> classrooms = parseClassroomsSheet(file);
        int createdClassrooms = classrooms.isEmpty() ? 0 : persistenceService.applyClassrooms(classrooms);

        List<String> detectedColumns = new ArrayList<>(parsed.detectedColumns);

        String summary = String.format(
                "Успешно обработано %d записей из %d, %d ошибок%s%s%s",
                result.processedLoads, parsed.totalDataRows, parsed.errors.size(),
                parsed.errors.isEmpty() ? "" : " в строках " + parsed.errors.stream()
                        .map(e -> String.valueOf(e.getRowNumber()))
                        .reduce((a, b) -> a + ", " + b).orElse(""),
                parsed.splitNotices.isEmpty() ? "" : String.format(
                        ". %d строк с разбитыми по запятой/`;` группами/дисциплинами стоит перепроверить",
                        parsed.splitNotices.size()),
                createdClassrooms == 0 ? "" : String.format(". Добавлено аудиторий: %d", createdClassrooms));

        return ImportReportDto.builder()
                .success(true)
                .applied(result.processedLoads > 0)
                .totalRows(parsed.totalDataRows)
                .processedRows(result.processedLoads)
                .errorRows(parsed.errors.size())
                .createdTeachers(result.createdTeachers)
                .createdDisciplines(result.createdDisciplines)
                .createdGroups(result.createdGroups)
                .createdLoads(result.createdLoads)
                .updatedLoads(result.updatedLoads)
                .createdClassrooms(createdClassrooms)
                .errors(parsed.errors)
                .splitNotices(parsed.splitNotices)
                .detectedColumns(detectedColumns)
                .summary(summary)
                .build();
    }

    public ImportPreviewDto previewImport(MultipartFile file, Integer academicYear) {
        ParseResult parsed = parseAndValidate(file);
        if (parsed.fatalError != null) {
            return ImportPreviewDto.builder()
                    .success(false)
                    .totalRows(0).exactCount(0).fuzzyCount(0).newCount(0)
                    .errorCount(parsed.fatalError.getErrorRows())
                    .rows(List.of())
                    .errors(parsed.fatalError.getErrors())
                    .splitNotices(List.of())
                    .summary(parsed.fatalError.getSummary())
                    .build();
        }

        List<Teacher> allTeachers = teacherRepository.findAll();
        List<ImportRowMatchDto> matchRows = new ArrayList<>();
        int exact = 0, fuzzy = 0, fresh = 0;

        for (int i = 0; i < parsed.validRows.size(); i++) {
            ParsedRow row = parsed.validRows.get(i);

            String status;
            MatchCandidate candidate;
            if (isBlank(row.teacherName)) {
                status = "AUTO";
                candidate = null;
            } else {
                candidate = findBestTeacherMatch(row.teacherName, allTeachers);
                if (candidate == null) {
                    status = "NEW";
                    fresh++;
                } else if (candidate.similarity >= 0.999) {
                    status = "EXACT";
                    exact++;
                } else if (candidate.similarity >= 0.70) {
                    status = "FUZZY";
                    fuzzy++;
                } else {
                    status = "NEW";
                    fresh++;
                    candidate = null;
                }
            }

            matchRows.add(ImportRowMatchDto.builder()
                    .rowIndex(i)
                    .excelRowNumber(row.rowNumber)
                    .teacherNameFromFile(row.teacherName)
                    .disciplineName(row.disciplineName)
                    .groupName(row.groupName)
                    .totalHours(row.totalHours)
                    .matchStatus(status)
                    .candidateTeacherId(candidate != null ? candidate.teacher.getId() : null)
                    .candidateTeacherName(candidate != null ? candidate.teacher.getFullName() : null)
                    .candidateDepartment(candidate != null ? candidate.teacher.getDepartment() : null)
                    .similarity(candidate != null ? candidate.similarity : 0.0)
                    .splitAmbiguous(row.splitAmbiguous)
                    .splitNote(row.splitNote)
                    .build());
        }

        Map<String, Integer> statusPriority = Map.of("EXACT", 0, "FUZZY", 1, "NEW", 2, "AUTO", 3);
        matchRows.sort(Comparator.comparingInt(r -> statusPriority.getOrDefault(r.getMatchStatus(), 9)));

        String summary = String.format(
                "Разобрано %d строк: %d точных совпадений, %d возможных дублей требуют проверки, %d новых преподавателей. " +
                        "%d ошибок.%s",
                parsed.validRows.size(), exact, fuzzy, fresh, parsed.errors.size(),
                parsed.splitNotices.isEmpty() ? "" : String.format(
                        " %d строк со списком через запятую/`;` в группе или дисциплине стоит перепроверить.",
                        parsed.splitNotices.size()));

        return ImportPreviewDto.builder()
                .success(true)
                .totalRows(parsed.validRows.size())
                .exactCount(exact)
                .fuzzyCount(fuzzy)
                .newCount(fresh)
                .errorCount(parsed.errors.size())
                .rows(matchRows)
                .errors(parsed.errors)
                .splitNotices(parsed.splitNotices)
                .detectedColumns(parsed.detectedColumns)
                .summary(summary)
                .build();
    }

    public ImportReportDto confirmImport(MultipartFile file, Integer academicYear,
                                          Map<Integer, ImportRowDecisionDto> decisions) {
        ParseResult parsed = parseAndValidate(file);
        if (parsed.fatalError != null) {
            return parsed.fatalError;
        }

        List<Teacher> allTeachers = teacherRepository.findAll();
        ImportPersistenceService.ImportResult result = persistenceService.applyRowsWithDecisions(
                parsed.validRows, academicYear, decisions, allTeachers);

        Map<String, String> teacherDisciplines = parseTeacherDisciplineSheet(file);
        if (!teacherDisciplines.isEmpty()) {
            persistenceService.applyTeacherDisciplines(teacherDisciplines);
        }

        Map<String, String> groupDisciplines = parseGroupDisciplinesSheet(file);
        if (!groupDisciplines.isEmpty()) {
            result.createdGroups += persistenceService.applyGroupsSheet(groupDisciplines);
        }

        List<ParsedClassroom> classrooms = parseClassroomsSheet(file);
        int createdClassrooms = classrooms.isEmpty() ? 0 : persistenceService.applyClassrooms(classrooms);

        String summary = String.format(
                "Импорт завершён: %d записей обработано (%d новых преподавателей, %d привязано к существующим), " +
                        "%d ошибок в исходных данных.%s%s",
                result.processedLoads, result.createdTeachers,
                parsed.validRows.size() - result.createdTeachers, parsed.errors.size(),
                parsed.splitNotices.isEmpty() ? "" : String.format(
                        " %d строк с разбитыми по запятой/`;` группами/дисциплинами стоит перепроверить.",
                        parsed.splitNotices.size()),
                createdClassrooms == 0 ? "" : String.format(" Добавлено аудиторий: %d.", createdClassrooms));

        return ImportReportDto.builder()
                .success(true)
                .applied(result.processedLoads > 0)
                .totalRows(parsed.totalDataRows)
                .processedRows(result.processedLoads)
                .errorRows(parsed.errors.size())
                .createdTeachers(result.createdTeachers)
                .createdDisciplines(result.createdDisciplines)
                .createdGroups(result.createdGroups)
                .createdLoads(result.createdLoads)
                .updatedLoads(result.updatedLoads)
                .createdClassrooms(createdClassrooms)
                .errors(parsed.errors)
                .splitNotices(parsed.splitNotices)
                .detectedColumns(parsed.detectedColumns)
                .summary(summary)
                .build();
    }

    private static final List<String> TEACHER_NAME_SYNONYMS = List.of(
            "фио преподавателя", "преподаватель", "фио", "педагог");
    private static final List<String> DISCIPLINES_LIST_SYNONYMS = List.of(
            "дисциплины", "ведёт дисциплины", "ведет дисциплины", "предметы", "какие дисциплины");
    private static final List<String> GROUP_NAME_SYNONYMS = List.of(
            "название группы", "группа", "группы", "учебная группа");
    private static final List<String> CLASSROOM_NAME_SYNONYMS = List.of(
            "аудитории", "аудитория", "кабинеты", "кабинет", "помещения", "помещение",
            "название аудитории", "номер аудитории");
    private static final List<String> CLASSROOM_CAPACITY_SYNONYMS = List.of(
            "вместимость", "вместимость, чел", "вместимость чел", "мест", "количество мест");
    private static final List<String> CLASSROOM_DISCIPLINES_SYNONYMS = List.of(
            "дисциплины", "предметы", "профиль", "специализация",
            "закреплённые дисциплины", "закрепленные дисциплины");

    private record SheetMatch(List<List<String>> rows, int headerRow, int colA, int colB) {
    }

    private SheetMatch findSheetWithColumns(MultipartFile file, List<String> colASynonyms, List<String> colBSynonyms) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (filename.endsWith(".csv")) return null;

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                List<List<String>> rows = readSheetRows(workbook.getSheetAt(s));
                if (rows.isEmpty()) continue;
                for (int i = 0; i < Math.min(rows.size(), 10); i++) {
                    int aCol = findColumn(rows.get(i), colASynonyms);
                    int bCol = findColumn(rows.get(i), colBSynonyms);
                    if (aCol >= 0 && bCol >= 0 && aCol != bCol) {
                        return new SheetMatch(rows, i, aCol, bCol);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Доп. листы книги не разобраны: {}", e.getMessage());
        }
        return null;
    }

    private List<List<String>> readSheetRows(Sheet sheet) {
        List<List<String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        int lastCol = 0;
        for (Row row : sheet) lastCol = Math.max(lastCol, row.getLastCellNum());
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < lastCol; c++) {
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
            }
            rows.add(cells);
        }
        return rows;
    }

    private Map<String, String> parseTeacherDisciplineSheet(MultipartFile file) {
        Map<String, String> result = new LinkedHashMap<>();
        SheetMatch match = findSheetWithColumns(file, TEACHER_NAME_SYNONYMS, DISCIPLINES_LIST_SYNONYMS);
        if (match == null) return result;

        for (int i = match.headerRow() + 1; i < match.rows().size(); i++) {
            List<String> row = match.rows().get(i);
            if (isRowBlank(row)) continue;
            String name = match.colA() < row.size() ? row.get(match.colA()) : null;
            String disciplines = match.colB() < row.size() ? row.get(match.colB()) : null;
            if (isBlank(name) || isBlank(disciplines)) continue;
            result.merge(name.trim(), disciplines.trim(), (a, b) -> a + ", " + b);
        }
        return result;
    }

    private Map<String, String> parseGroupDisciplinesSheet(MultipartFile file) {
        Map<String, String> result = new LinkedHashMap<>();
        SheetMatch match = findSheetWithColumns(file, GROUP_NAME_SYNONYMS, DISCIPLINES_LIST_SYNONYMS);
        if (match == null) return result;

        for (int i = match.headerRow() + 1; i < match.rows().size(); i++) {
            List<String> row = match.rows().get(i);
            if (isRowBlank(row)) continue;
            String name = match.colA() < row.size() ? row.get(match.colA()) : null;
            String disciplines = match.colB() < row.size() ? row.get(match.colB()) : null;
            if (isBlank(name)) continue;
            String value = disciplines == null ? "" : disciplines.trim();
            result.merge(name.trim(), value, (a, b) -> {
                if (a.isBlank()) return b;
                if (b.isBlank()) return a;
                return a + ", " + b;
            });
        }
        return result;
    }

    record ParsedClassroom(String name, Integer capacity, List<String> allowedDisciplines) {
    }

    private List<ParsedClassroom> parseClassroomsSheet(MultipartFile file) {
        List<ParsedClassroom> result = new ArrayList<>();
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (filename.endsWith(".csv")) return result;

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                List<List<String>> rows = readSheetRows(workbook.getSheetAt(s));
                if (rows.isEmpty()) continue;

                int headerRow = -1, nameCol = -1, capCol = -1, discCol = -1;
                for (int i = 0; i < Math.min(rows.size(), 10); i++) {
                    int nc = findColumn(rows.get(i), CLASSROOM_NAME_SYNONYMS);
                    if (nc >= 0) {
                        headerRow = i;
                        nameCol = nc;
                        capCol = findColumn(rows.get(i), CLASSROOM_CAPACITY_SYNONYMS);
                        discCol = findColumn(rows.get(i), CLASSROOM_DISCIPLINES_SYNONYMS);
                        break;
                    }
                }
                if (headerRow < 0) continue;

                for (int i = headerRow + 1; i < rows.size(); i++) {
                    List<String> row = rows.get(i);
                    if (isRowBlank(row)) continue;
                    String name = nameCol < row.size() ? row.get(nameCol) : null;
                    if (isBlank(name)) continue;

                    Integer capacity = null;
                    if (capCol >= 0 && capCol < row.size() && !isBlank(row.get(capCol))) {
                        try {
                            capacity = Integer.parseInt(row.get(capCol).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    List<String> disciplines = List.of();
                    if (discCol >= 0 && discCol < row.size() && !isBlank(row.get(discCol))) {
                        disciplines = splitTokens(row.get(discCol)).stream()
                                .filter(t -> t != null && !t.isBlank())
                                .map(String::trim)
                                .toList();
                    }
                    result.add(new ParsedClassroom(name.trim(), capacity, disciplines));
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("Лист с аудиториями не разобран: {}", e.getMessage());
        }
        return result;
    }

    private int findColumn(List<String> headerRow, List<String> synonyms) {
        for (int col = 0; col < headerRow.size(); col++) {
            String cell = normalize(headerRow.get(col));
            if (cell.isEmpty()) continue;
            for (String syn : synonyms) {
                if (cell.contains(syn)) return col;
            }
        }
        return -1;
    }

    private static final java.util.regex.Pattern MULTI_VALUE_SPLIT = java.util.regex.Pattern.compile("[,;]+");

    private List<String> splitTokens(String raw) {
        if (raw == null) return Collections.singletonList(null);
        List<String> tokens = new ArrayList<>();
        for (String part : MULTI_VALUE_SPLIT.split(raw)) {
            String t = part.trim();
            if (!t.isEmpty()) tokens.add(t);
        }
        return tokens.isEmpty() ? Collections.singletonList(raw) : tokens;
    }

    private ParsedRow copyRow(ParsedRow src) {
        ParsedRow copy = new ParsedRow();
        copy.rowNumber = src.rowNumber;
        copy.teacherName = src.teacherName;
        copy.candidateTeachers = src.candidateTeachers;
        copy.disciplineName = src.disciplineName;
        copy.groupName = src.groupName;
        copy.totalHours = src.totalHours;
        copy.hours1 = src.hours1;
        copy.hours2 = src.hours2;
        copy.hoursPerWeek = src.hoursPerWeek;
        copy.lessonType = src.lessonType;
        copy.preferredDaysTime = src.preferredDaysTime;
        copy.department = src.department;
        copy.controlPointType = src.controlPointType;
        return copy;
    }

    private List<ParsedRow> expandMultiValueRows(ParsedRow raw) {
        List<String> groupTokens = isBlank(raw.groupName) ? Collections.singletonList(raw.groupName) : splitTokens(raw.groupName);
        List<String> disciplineTokens = isBlank(raw.disciplineName) ? Collections.singletonList(raw.disciplineName) : splitTokens(raw.disciplineName);

        if (groupTokens.size() <= 1 && disciplineTokens.size() <= 1) {
            return List.of(raw);
        }

        boolean groupsAllKnown = groupTokens.size() <= 1 || groupTokens.stream()
                .allMatch(g -> studyGroupRepository.findByNameIgnoreCase(g).isPresent());
        boolean groupsNoneKnown = groupTokens.size() <= 1 || groupTokens.stream()
                .noneMatch(g -> studyGroupRepository.findByNameIgnoreCase(g).isPresent());
        boolean disciplinesAllKnown = disciplineTokens.size() <= 1 || disciplineTokens.stream()
                .allMatch(d -> disciplineRepository.findByNameIgnoreCase(d).isPresent());
        boolean disciplinesNoneKnown = disciplineTokens.size() <= 1 || disciplineTokens.stream()
                .noneMatch(d -> disciplineRepository.findByNameIgnoreCase(d).isPresent());

        boolean mixedGroup = groupTokens.size() > 1 && !groupsAllKnown && !groupsNoneKnown;
        boolean mixedDiscipline = disciplineTokens.size() > 1 && !disciplinesAllKnown && !disciplinesNoneKnown;

        List<String> notes = new ArrayList<>();
        boolean ambiguous = false;
        if (groupTokens.size() > 1) {
            notes.add("группа \"" + raw.groupName + "\" разбита на " + groupTokens.size() + ": " + String.join(", ", groupTokens));
            if (mixedGroup) {
                notes.add("часть значений группы не найдена в базе как отдельная группа — возможно, это одно название, а не список");
                ambiguous = true;
            } else if (groupsNoneKnown) {
                notes.add("все получившиеся группы новые (ещё не заводились) — проверьте, что разбиение верное");
                ambiguous = true;
            }
        }
        if (disciplineTokens.size() > 1) {
            notes.add("дисциплина \"" + raw.disciplineName + "\" разбита на " + disciplineTokens.size() + ": " + String.join(", ", disciplineTokens));
            if (mixedDiscipline) {
                notes.add("часть значений дисциплины не найдена в базе как отдельная дисциплина — возможно, это одно название с запятой внутри, а не список");
                ambiguous = true;
            } else if (disciplinesNoneKnown) {
                notes.add("все получившиеся дисциплины новые (ещё не заводились) — проверьте, что разбиение верное");
                ambiguous = true;
            }
        }

        String note = String.join("; ", notes);
        List<ParsedRow> result = new ArrayList<>();
        for (String g : groupTokens) {
            for (String d : disciplineTokens) {
                ParsedRow copy = copyRow(raw);
                copy.groupName = g;
                copy.disciplineName = d;
                copy.splitAmbiguous = ambiguous;
                copy.splitNote = note;
                result.add(copy);
            }
        }
        return result;
    }

    private static class ParseResult {
        List<ParsedRow> validRows = new ArrayList<>();
        List<ImportRowErrorDto> errors = new ArrayList<>();
        List<ImportRowErrorDto> splitNotices = new ArrayList<>();
        List<String> detectedColumns = new ArrayList<>();
        int totalDataRows = 0;
        ImportReportDto fatalError;
    }

    private ParseResult parseAndValidate(MultipartFile file) {
        ParseResult result = new ParseResult();
        List<List<String>> rows;
        try {
            rows = readAllRows(file);
        } catch (Exception e) {
            log.error("Не удалось прочитать файл импорта: {}", e.getMessage(), e);
            result.fatalError = ImportReportDto.builder()
                    .success(false).applied(false)
                    .totalRows(0).processedRows(0).errorRows(0)
                    .errors(List.of(ImportRowErrorDto.builder()
                            .rowNumber(0)
                            .message("Не удалось прочитать файл: " + e.getMessage())
                            .build()))
                    .summary("Импорт не выполнен: файл повреждён или имеет неподдерживаемый формат")
                    .build();
            return result;
        }

        if (rows.isEmpty()) {
            result.fatalError = ImportReportDto.builder()
                    .success(false).applied(false)
                    .totalRows(0).processedRows(0).errorRows(0)
                    .errors(List.of())
                    .summary("Файл пуст")
                    .build();
            return result;
        }

        int headerRowIndex = detectHeaderRow(rows);
        if (headerRowIndex < 0) {
            result.fatalError = ImportReportDto.builder()
                    .success(false).applied(false)
                    .totalRows(rows.size()).processedRows(0).errorRows(0)
                    .errors(List.of())
                    .summary("Не удалось распознать заголовки колонок. Убедитесь, что в файле " +
                            "есть колонки Дисциплина и Группа (ФИО преподавателя необязательно)")
                    .build();
            return result;
        }
        Map<Field, Integer> columnMap = detectColumns(rows.get(headerRowIndex));
        columnMap.keySet().forEach(f -> result.detectedColumns.add(f.name()));

        Set<String> seenKeys = new HashSet<>();

        for (int i = headerRowIndex + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (isRowBlank(row)) continue;
            result.totalDataRows++;
            int excelRowNumber = i + 1;

            try {
                ParsedRow rawParsed = parseRow(row, columnMap, excelRowNumber);

                for (ParsedRow parsed : expandMultiValueRows(rawParsed)) {

                    if (isBlank(parsed.disciplineName)) {
                        result.errors.add(rowError(excelRowNumber, "Не указана дисциплина", row));
                        continue;
                    }
                    if (isBlank(parsed.groupName)) {
                        result.errors.add(rowError(excelRowNumber, "Не указана группа", row));
                        continue;
                    }
                    if (parsed.totalHours != null && parsed.totalHours < 0) {
                        result.errors.add(rowError(excelRowNumber, "Отрицательное значение часов за год", row));
                        continue;
                    }
                    if (parsed.hoursPerWeek != null && parsed.hoursPerWeek < 0) {
                        result.errors.add(rowError(excelRowNumber, "Отрицательное значение часов в неделю", row));
                        continue;
                    }
                    if (parsed.totalHours == null && parsed.hours1 == null && parsed.hours2 == null) {
                        result.errors.add(rowError(excelRowNumber, "Не указаны плановые часы (год или по семестрам)", row));
                        continue;
                    }

                    double effectiveHoursPerWeek = parsed.hoursPerWeek != null
                            ? parsed.hoursPerWeek
                            : (parsed.totalHours != null ? parsed.totalHours / 36.0 : 0);
                    if (effectiveHoursPerWeek > 36) {
                        result.errors.add(rowError(excelRowNumber, String.format(
                                "Слишком много часов в неделю на одну дисциплину: %.1f ч (максимум 36 ч/нед = 18 пар). " +
                                        "Проверьте колонку \"Часов за год\"/\"Часов в неделю\" — похоже, туда попало " +
                                        "суммарное/годовое число вместо недельной нагрузки", effectiveHoursPerWeek), row));
                        continue;
                    }

                    String teacherKeyPart = isBlank(parsed.teacherName) ? "__AUTO__" : normalize(parsed.teacherName);
                    String dedupKey = teacherKeyPart + "|" + normalize(parsed.disciplineName)
                            + "|" + normalize(parsed.groupName) + "|" + normalize(parsed.lessonType);
                    if (!seenKeys.add(dedupKey)) {
                        result.errors.add(rowError(excelRowNumber,
                                "Дубликат строки (тот же преподаватель/дисциплина/группа уже встречался в файле" +
                                        " — включая варианты, полученные разбиением списка через запятую/`;`)", row));
                        continue;
                    }

                    if (parsed.splitAmbiguous) {
                        result.splitNotices.add(rowError(excelRowNumber,
                                "Уточните разбиение: " + parsed.splitNote, row));
                    }

                    result.validRows.add(parsed);
                }
            } catch (Exception e) {
                result.errors.add(rowError(excelRowNumber, "Ошибка обработки строки: " + e.getMessage(), row));
            }
        }

        result.errors.sort(Comparator.comparingInt(e -> e.getMessage() != null && e.getMessage().startsWith("Дубликат") ? 0 : 1));

        return result;
    }

    private static class MatchCandidate {
        Teacher teacher;
        double similarity;
    }

    private MatchCandidate findBestTeacherMatch(String nameFromFile, List<Teacher> allTeachers) {
        MatchCandidate best = null;
        for (Teacher t : allTeachers) {
            double sim = com.karyakina.schedule.util.StringSimilarity.similarity(nameFromFile, t.getFullName());
            if (best == null || sim > best.similarity) {
                best = new MatchCandidate();
                best.teacher = t;
                best.similarity = sim;
            }
        }
        return best;
    }

    private List<List<String>> readAllRows(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (filename.endsWith(".csv")) {
            return readCsv(file.getInputStream());
        }
        return readExcel(file.getInputStream());
    }

    private List<List<String>> readExcel(InputStream is) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(is)) {
            List<List<String>> firstSheetRows = readSheetRows(workbook.getSheetAt(0));
            if (hasRecognizableLoadHeader(firstSheetRows)) {
                return firstSheetRows;
            }
            for (int s = 1; s < workbook.getNumberOfSheets(); s++) {
                List<List<String>> rows = readSheetRows(workbook.getSheetAt(s));
                if (hasRecognizableLoadHeader(rows)) {
                    return rows;
                }
            }
            return firstSheetRows;
        }
    }

    private boolean hasRecognizableLoadHeader(List<List<String>> rows) {
        return detectHeaderRow(rows) >= 0;
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
        if (bestRow >= 0) {
            Map<Field, Integer> map = detectColumns(rows.get(bestRow));
            if (!map.containsKey(Field.DISCIPLINE) || !map.containsKey(Field.GROUP)) {
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
                if (result.containsKey(entry.getKey())) continue;
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
        parsed.candidateTeachers = emptyToNull(getCell(row, columnMap, Field.CANDIDATE_TEACHERS));
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
