package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.StudyGroup;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.StudyGroupRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.*;

/**
 * Экспорт расписания за календарный месяц в Excel — в виде печатной формы, привычной
 * учебной части: "УТВЕРЖДАЮ" в шапке, таблица "День недели / № урока" по строкам и
 * группы (название + № кабинета) по столбцам, один лист на календарную неделю месяца.
 * Формат подсмотрен в реальном расписании техникума (см. пример, приложенный к задаче) —
 * не претендует на побайтовое совпадение оформления, но повторяет структуру таблицы,
 * которую распечатывают и вешают на стенд/публикуют на сайте.
 *
 * <p>Источник данных — {@link Schedule} (недельный шаблон), а не {@code LessonInstance}:
 * это то же самое, что видит администратор на странице "Расписание пар", и то, что
 * реально приходит из автосоставления. Если у записи расписания задан конкретный
 * {@code academicWeek} (числитель/знаменатель), для каждой даты месяца подбирается
 * подходящая запись через {@link LessonInstanceService#computeAcademicWeek}; если для
 * пары в этот день недели есть только запись "на каждую неделю" (academicWeek == null),
 * используется она.
 */
@Service
@RequiredArgsConstructor
public class ScheduleExportService {

    private final ScheduleRepository scheduleRepository;
    private final StudyGroupRepository groupRepository;
    private final LessonInstanceService lessonInstanceService;

    private static final String[] MONTH_GENITIVE = {
            "", "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };

    /**
     * @param academicYear учебный год записи (см. Schedule.academicYear / AcademicYearUtil)
     * @param month        календарный месяц 1-12
     */
    public byte[] exportMonth(int academicYear, int month, String orgName, String directorName) throws IOException {
        LocalDate monthStart = LocalDate.of(academicYear, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());

        List<StudyGroup> groups = new ArrayList<>(groupRepository.findAll());
        groups.sort(Comparator.comparing(StudyGroup::getName, Comparator.nullsLast(String::compareTo)));

        List<Schedule> schedules = scheduleRepository.findByAcademicYear(academicYear);
        // Индекс для быстрого поиска: группа+день недели+№ пары -> все варианты (в т.ч. по неделям).
        Map<String, List<Schedule>> byGroupDayPair = new HashMap<>();
        for (Schedule s : schedules) {
            if (s.getTeacherLoad() == null || s.getTeacherLoad().getGroup() == null) continue;
            int pairIdx = GenerationGrid.pairIndex(s.getStartTime());
            if (pairIdx < 0) continue;
            String key = s.getTeacherLoad().getGroup().getId() + "|" + s.getDayOfWeek() + "|" + pairIdx;
            byGroupDayPair.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Styles styles = new Styles(workbook);

            // Разбиваем месяц на календарные недели (понедельник-воскресенье), по одному листу
            // на каждую неделю, пересекающуюся с месяцем — совсем как отдельные вкладки-недели
            // в примере. В лист попадают только рабочие дни (WORK_DAYS), реально входящие
            // в этот месяц (хвост недели за пределами месяца не показываем — он будет в
            // соседнем месяце).
            LocalDate weekCursor = monthStart.with(WeekFields.ISO.getFirstDayOfWeek());
            int sheetIndex = 0;
            while (!weekCursor.isAfter(monthEnd)) {
                LocalDate weekEnd = weekCursor.plusDays(6);
                List<LocalDate> daysInMonth = new ArrayList<>();
                for (LocalDate d = weekCursor; !d.isAfter(weekEnd); d = d.plusDays(1)) {
                    if (!d.isBefore(monthStart) && !d.isAfter(monthEnd)
                            && GenerationGrid.dayIndex(d.getDayOfWeek()) >= 0) {
                        daysInMonth.add(d);
                    }
                }
                if (!daysInMonth.isEmpty()) {
                    sheetIndex++;
                    String sheetName = sheetTitle(daysInMonth, sheetIndex);
                    Sheet sheet = workbook.createSheet(sheetName);
                    buildWeekSheet(sheet, styles, daysInMonth, groups, byGroupDayPair, academicYear,
                            orgName, directorName);
                }
                weekCursor = weekCursor.plusWeeks(1);
            }

            if (workbook.getNumberOfSheets() == 0) {
                // Пустой месяц (например, нет ни одной рабочей даты) — отдаём лист-заглушку,
                // чтобы файл не был битым и было видно, что данных нет, а не что что-то сломалось.
                Sheet sheet = workbook.createSheet("Нет данных");
                sheet.createRow(0).createCell(0)
                        .setCellValue("На " + MONTH_GENITIVE[month] + " " + academicYear + " г. расписание не найдено.");
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private String sheetTitle(List<LocalDate> daysInMonth, int index) {
        LocalDate first = daysInMonth.get(0);
        LocalDate last = daysInMonth.get(daysInMonth.size() - 1);
        String raw = pad(first.getDayOfMonth()) + "." + pad(first.getMonthValue()) + "-"
                + pad(last.getDayOfMonth()) + "." + pad(last.getMonthValue());
        // Excel не разрешает : \ / ? * [ ] в названии листа и ограничивает длину 31 символом —
        // наш формат их не использует, но на всякий случай подрежем длину.
        return raw.length() > 31 ? ("Неделя " + index) : raw;
    }

    private String pad(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private void buildWeekSheet(Sheet sheet, Styles styles, List<LocalDate> days, List<StudyGroup> groups,
                                Map<String, List<Schedule>> byGroupDayPair, int academicYear,
                                String orgName, String directorName) {
        int pairsPerDay = GenerationGrid.pairsPerDay();
        int groupCols = groups.size() * 2; // название/дисциплина + № кабинета на группу
        int lastCol = 1 + groupCols; // col0 = День недели, col1 = № урока, далее группы

        int row = 0;

        // ---- Гриф утверждения (сверху справа) ----
        Row approveRow = sheet.createRow(row);
        Cell approveCell = approveRow.createCell(Math.max(0, lastCol - 5));
        approveCell.setCellValue("УТВЕРЖДАЮ\n"
                + (orgName == null || orgName.isBlank() ? "и.о.директора" : "и.о.директора " + orgName) + "\n"
                + "______________ / " + (directorName == null || directorName.isBlank() ? "___________" : directorName) + " /\n"
                + "«___» ______________ " + academicYear + " г.");
        approveCell.setCellStyle(styles.approveBlock);
        sheet.addMergedRegion(new CellRangeAddress(row, row, Math.max(0, lastCol - 5), lastCol));
        row += 3;

        // ---- Заголовок ----
        row = mergedTitleRow(sheet, styles, row, lastCol, "РАСПИСАНИЕ", styles.titleBig);
        row = mergedTitleRow(sheet, styles, row, lastCol,
                "занятий учебных групп" + (orgName == null || orgName.isBlank() ? "" : " " + orgName), styles.titleSmall);
        LocalDate first = days.get(0);
        LocalDate last = days.get(days.size() - 1);
        row = mergedTitleRow(sheet, styles, row, lastCol, String.format("в период с «%d» %s по «%d» %s %d г.",
                first.getDayOfMonth(), MONTH_GENITIVE[first.getMonthValue()],
                last.getDayOfMonth(), MONTH_GENITIVE[last.getMonthValue()], last.getYear()), styles.titleSmall);
        row++; // пустая строка-отступ

        // ---- Шапка таблицы: 3 строки (название группы+кабинет / курс / "Учебная дисциплина") ----
        int headerTop = row;
        int headerBottom = row + 2;
        Row nameRow = sheet.createRow(headerTop);
        Row courseRow = sheet.createRow(headerTop + 1);
        Row labelRow = sheet.createRow(headerTop + 2);

        headerCell(nameRow, 0, "День недели", styles.headerCell);
        headerCell(nameRow, 1, "№ урока", styles.headerCell);
        sheet.addMergedRegion(new CellRangeAddress(headerTop, headerBottom, 0, 0));
        sheet.addMergedRegion(new CellRangeAddress(headerTop, headerBottom, 1, 1));
        for (int r = headerTop; r <= headerBottom; r++) {
            sheet.getRow(r); // гарантируем существование строк для стилей объединения
        }

        for (int g = 0; g < groups.size(); g++) {
            StudyGroup group = groups.get(g);
            int col = 2 + g * 2;
            headerCell(nameRow, col, group.getName(), styles.headerCell);
            headerCell(nameRow, col + 1, "№ кабинета", styles.headerCell);
            String courseText = group.getCourse() != null ? group.getCourse() + " курс" : "";
            headerCell(courseRow, col, courseText, styles.headerCell);
            sheet.addMergedRegion(new CellRangeAddress(headerTop + 1, headerTop + 1, col, col + 1));
            headerCell(labelRow, col, "Учебная дисциплина, МДК", styles.headerCellSmall);
            sheet.addMergedRegion(new CellRangeAddress(headerTop + 2, headerTop + 2, col, col + 1));
        }
        row = headerBottom + 1;

        // ---- Тело таблицы: дата (одна строка) + пары дня ----
        for (LocalDate date : days) {
            int academicWeek = lessonInstanceService.computeAcademicWeek(date, academicYear);
            int dayIdx = GenerationGrid.dayIndex(date.getDayOfWeek());

            Row dateRow = sheet.createRow(row);
            Cell dateCell = dateRow.createCell(0);
            dateCell.setCellValue(GenerationGrid.dayName(dayIdx).toUpperCase() + "  "
                    + pad(date.getDayOfMonth()) + "." + pad(date.getMonthValue()) + "."
                    + date.getYear());
            dateCell.setCellStyle(styles.dateCell);
            sheet.addMergedRegion(new CellRangeAddress(row, row, 0, lastCol));
            row++;

            for (int pairIdx = 0; pairIdx < pairsPerDay; pairIdx++) {
                Row lessonRow = sheet.createRow(row);
                Cell lessonNumberCell = lessonRow.createCell(1);
                // Нумерация "1, 3, 5, 7..." — как в примере: пара считается по номеру
                // её первого академического часа (пара = 2 часа).
                lessonNumberCell.setCellValue(pairIdx * 2 + 1);
                lessonNumberCell.setCellStyle(styles.dataCellCenter);

                for (int g = 0; g < groups.size(); g++) {
                    StudyGroup group = groups.get(g);
                    int col = 2 + g * 2;
                    Schedule found = pickSchedule(byGroupDayPair, group.getId(), date.getDayOfWeek(),
                            pairIdx, academicWeek);
                    Cell disciplineCell = lessonRow.createCell(col);
                    Cell roomCell = lessonRow.createCell(col + 1);
                    if (found != null) {
                        disciplineCell.setCellValue(found.getTeacherLoad().getDiscipline().getName());
                        roomCell.setCellValue(found.getClassroom() == null ? "" : found.getClassroom());
                    }
                    disciplineCell.setCellStyle(styles.dataCell);
                    roomCell.setCellStyle(styles.dataCellCenter);
                }
                row++;
            }
        }

        sheet.setColumnWidth(0, 14 * 256);
        sheet.setColumnWidth(1, 8 * 256);
        for (int g = 0; g < groups.size(); g++) {
            sheet.setColumnWidth(2 + g * 2, 22 * 256);
            sheet.setColumnWidth(3 + g * 2, 10 * 256);
        }
        sheet.createFreezePane(2, headerBottom + 1);
    }

    /** Выбирает подходящую запись: сперва — привязанную именно к этой учебной неделе, иначе — "на каждую неделю". */
    private Schedule pickSchedule(Map<String, List<Schedule>> byGroupDayPair, Long groupId, DayOfWeek dayOfWeek,
                                  int pairIdx, int academicWeek) {
        List<Schedule> candidates = byGroupDayPair.get(groupId + "|" + dayOfWeek + "|" + pairIdx);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (Schedule s : candidates) {
            if (s.getAcademicWeek() != null && s.getAcademicWeek() == academicWeek) {
                return s;
            }
        }
        for (Schedule s : candidates) {
            if (s.getAcademicWeek() == null) {
                return s;
            }
        }
        return null;
    }

    private int mergedTitleRow(Sheet sheet, Styles styles, int row, int lastCol, String text, CellStyle style) {
        Row r = sheet.createRow(row);
        Cell c = r.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(style);
        if (lastCol > 0) {
            sheet.addMergedRegion(new CellRangeAddress(row, row, 0, lastCol));
        }
        return row + 1;
    }

    private void headerCell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        c.setCellStyle(style);
    }

    /** Именованные стили, чтобы не плодить одинаковые объекты CellStyle на каждую ячейку. */
    private static final class Styles {
        final CellStyle titleBig;
        final CellStyle titleSmall;
        final CellStyle approveBlock;
        final CellStyle headerCell;
        final CellStyle headerCellSmall;
        final CellStyle dateCell;
        final CellStyle dataCell;
        final CellStyle dataCellCenter;

        Styles(Workbook wb) {
            Font bold14 = wb.createFont();
            bold14.setBold(true);
            bold14.setFontHeightInPoints((short) 14);

            Font bold11 = wb.createFont();
            bold11.setBold(true);
            bold11.setFontHeightInPoints((short) 11);

            Font plain9 = wb.createFont();
            plain9.setFontHeightInPoints((short) 9);

            titleBig = wb.createCellStyle();
            titleBig.setFont(bold14);
            titleBig.setAlignment(HorizontalAlignment.CENTER);

            titleSmall = wb.createCellStyle();
            titleSmall.setFont(bold11);
            titleSmall.setAlignment(HorizontalAlignment.CENTER);

            approveBlock = wb.createCellStyle();
            approveBlock.setFont(plain9);
            approveBlock.setWrapText(true);
            approveBlock.setAlignment(HorizontalAlignment.LEFT);

            headerCell = wb.createCellStyle();
            headerCell.setFont(bold11);
            headerCell.setAlignment(HorizontalAlignment.CENTER);
            headerCell.setVerticalAlignment(VerticalAlignment.CENTER);
            headerCell.setWrapText(true);
            setBorders(headerCell);

            headerCellSmall = wb.createCellStyle();
            headerCellSmall.setFont(plain9);
            headerCellSmall.setAlignment(HorizontalAlignment.CENTER);
            headerCellSmall.setWrapText(true);
            setBorders(headerCellSmall);

            dateCell = wb.createCellStyle();
            dateCell.setFont(bold11);
            dateCell.setAlignment(HorizontalAlignment.LEFT);
            setBorders(dateCell);

            dataCell = wb.createCellStyle();
            dataCell.setWrapText(true);
            setBorders(dataCell);

            dataCellCenter = wb.createCellStyle();
            dataCellCenter.setAlignment(HorizontalAlignment.CENTER);
            setBorders(dataCellCenter);
        }

        private void setBorders(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
        }
    }
}
