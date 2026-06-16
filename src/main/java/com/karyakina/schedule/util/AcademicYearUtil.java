package com.karyakina.schedule.util;

import java.time.LocalDate;

public class AcademicYearUtil {

    private AcademicYearUtil() {}

    /**
     * Определяет текущий учебный год на основе системной даты.
     * Формула:
     * - Сентябрь (9) - Декабрь (12): учебный год = текущий-следующий (2025-2026)
     * - Январь (1) - Май (5): учебный год = прошлый-текущий (2024-2025)
     * - Июнь (6) - Август (8): учебный год = текущий-следующий (2025-2026)
     *
     * @return массив [startYear, endYear]
     */
    public static int[] getCurrentAcademicYear() {
        int currentYear = LocalDate.now().getYear();
        int month = LocalDate.now().getMonthValue();
        
        // Сентябрь (9) - Декабрь (12) -> 2025-2026
        if (month >= 9 && month <= 12) {
            return new int[]{currentYear, currentYear + 1};
        }
        // Январь (1) - Май (5) -> 2024-2025
        else if (month >= 1 && month <= 5) {
            return new int[]{currentYear - 1, currentYear};
        }
        // Июнь (6) - Август (8) -> 2025-2026
        else {
            return new int[]{currentYear, currentYear + 1};
        }
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
