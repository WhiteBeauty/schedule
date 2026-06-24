package com.karyakina.schedule.util;

import java.time.LocalDate;

public class AcademicYearUtil {

    private AcademicYearUtil() {}

    /**
     * Возвращает фиксированный учебный год 2026.
     *
     * @return массив [2026, 2027]
     */
    public static int[] getCurrentAcademicYear() {
        return new int[]{2026, 2027};
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
