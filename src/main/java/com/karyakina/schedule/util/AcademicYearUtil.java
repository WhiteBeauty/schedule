package com.karyakina.schedule.util;

import java.time.LocalDate;

public class AcademicYearUtil {

    private AcademicYearUtil() {}

    /**
     * Учебный год определяется по РЕАЛЬНОЙ текущей дате, а не захардкожен: считаем,
     * что учебный год стартует 1 сентября. Если сегодня сентябрь-декабрь — учебный
     * год [текущий_год, текущий_год+1]; если январь-август — [текущий_год-1, текущий_год]
     * (мы всё ещё во второй половине года, начавшегося прошлой осенью).
     * Это напрямую влияет на расчёт продуктивности/помесячного учёта — без привязки
     * к реальной дате "прогресс учебного года" и текущий месяц для агрегации были
     * бы вычислены неверно.
     *
     * @return массив [startYear, endYear]
     */
    public static int[] getCurrentAcademicYear() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        if (today.getMonthValue() >= 9) {
            return new int[]{year, year + 1};
        }
        return new int[]{year - 1, year};
    }

    /**
     * Возвращает строковое представление учебного года (например "2025-2026")
     */
    public static String getAcademicYearString() {
        int[] years = getCurrentAcademicYear();
        return years[0] + "-" + years[1];
    }

    /**
     * Возвращает основной год учебного года (начальный)
     */
    public static int getCurrentAcademicYearStart() {
        return getCurrentAcademicYear()[0];
    }
}
