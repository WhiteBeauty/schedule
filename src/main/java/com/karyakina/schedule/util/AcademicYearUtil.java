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

    /**
     * Сколько учебных недель в ОДНОМ семестре (используется при расчёте часов в неделю
     * из {@code TeacherLoad.firstSemesterHours}/{@code secondSemesterHours} —
     * см. ScheduleGeneratorService). 18 недель на семестр — половина от полного
     * учебного года (APPROX_WEEKS_PER_YEAR = 36 в ScheduleGeneratorService), что
     * соответствует типичному распределению для СПО (примерно по одному полугодию
     * на семестр без учёта каникул/сессии).
     */
    public static final int WEEKS_PER_SEMESTER = 18;

    /**
     * Текущий семестр (1 или 2) по календарной дате. Используется автосоставлением
     * расписания, чтобы считать часы в неделю от часов ИМЕННО текущего семестра
     * (firstSemesterHours/secondSemesterHours), а не от суммы часов за весь год —
     * иначе дисциплины, которые реально идут только в одном полугодии, "размазываются"
     * по всем 36 неделям и недельная нагрузка преподавателя считается неверно (см. баг
     * с завышенным/заниженным перегрузом при большом числе разных по семестрам
     * дисциплин у одного преподавателя).
     *
     * Сентябрь-январь — 1 семестр, февраль-август — 2 семестр (стандартный календарь
     * для СПО: летние каникулы относятся к завершению 2 семестра предыдущего года).
     */
    public static int getCurrentSemester() {
        int month = LocalDate.now().getMonthValue();
        return (month >= 9 || month == 1) ? 1 : 2;
    }
}
