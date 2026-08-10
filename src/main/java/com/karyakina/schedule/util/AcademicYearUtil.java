package com.karyakina.schedule.util;

import java.time.LocalDate;

public class AcademicYearUtil {

    private AcademicYearUtil() {}

    /**
     * Учебный год определяется по РЕАЛЬНОЙ текущей дате, а не захардкожен.
     * Начало учебного года — это текущий календарный год (LocalDate.now().getYear()),
     * то есть весь 2026 год отображается как учебный год "2026-2027". Раньше здесь
     * была логика с переходом 1 сентября (год начинался только с сентября, а до этого
     * показывался предыдущий год) — из-за неё интерфейс до сентября показывал
     * "прошлый" год, что визуально выглядело как ошибка ("сейчас 2026, а везде 2025").
     * Поэтому переход убран: год всегда совпадает с текущим календарным годом.
     *
     * @return массив [startYear, endYear]
     */
    public static int[] getCurrentAcademicYear() {
        int year = LocalDate.now().getYear();
        return new int[]{year, year + 1};
    }

    /**
     * Возвращает строковое представление учебного года (например "2026-2027")
     */
    public static String getAcademicYearString() {
        int[] years = getCurrentAcademicYear();
        return years[0] + "-" + years[1];
    }

    /**
     * Возвращает основной год учебного года (начальный) — совпадает с текущим
     * календарным годом.
     */
    public static int getCurrentAcademicYearStart() {
        return getCurrentAcademicYear()[0];
    }
}
