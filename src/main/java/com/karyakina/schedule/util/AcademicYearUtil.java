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
     * Текущий семестр (1 или 2) по календарной дате. Используется автосоставлением
     * расписания, чтобы считать часы в неделю от часов ИМЕННО текущего семестра
     * (firstSemesterHours/secondSemesterHours), а не от суммы часов за весь год —
     * иначе дисциплины, которые реально идут только в одном полугодии, "размазываются"
     * по всем 36 неделям и недельная нагрузка преподавателя считается неверно (см. баг
     * с завышенным/заниженным перегрузом при большом числе разных по семестрам
     * дисциплин у одного преподавателя).
     *
     * ТЗ фиксирует точные границы семестров (см. {@link #SEM1_START} и др.) — раньше
     * здесь была грубая эвристика по месяцу (сентябрь-январь / остальное), не различавшая
     * каникулы. На каникулах возвращает ближайший ПРЕДСТОЯЩИЙ семестр (чтобы можно было
     * готовить расписание заранее) — используйте {@link #isVacation(LocalDate)}, если
     * нужно узнать именно "идут ли сейчас занятия".
     */
    public static int getCurrentSemester() {
        return semesterOf(LocalDate.now(), true);
    }

    /** 1 сентября — 30 декабря. */
    public static final java.time.MonthDay SEM1_START = java.time.MonthDay.of(9, 1);
    public static final java.time.MonthDay SEM1_END = java.time.MonthDay.of(12, 30);
    /** 14 января — 30 июня. */
    public static final java.time.MonthDay SEM2_START = java.time.MonthDay.of(1, 14);
    public static final java.time.MonthDay SEM2_END = java.time.MonthDay.of(6, 30);

    /** true, если дата приходится на каникулы (не входит ни в один семестр) — занятий и расписания в этот день нет. */
    public static boolean isVacation(LocalDate date) {
        return semesterOf(date, false) == 0;
    }

    /**
     * Семестр для конкретной даты: 1, 2, или 0 — если дата на каникулах (зимних между
     * семестрами, 31 декабря — 13 января, или летних, июль-август).
     *
     * @param upcomingIfVacation на каникулах вернуть 0 (false) или ближайший ПРЕДСТОЯЩИЙ
     *                           семестр (true) — см. {@link #getCurrentSemester()}
     */
    private static int semesterOf(LocalDate date, boolean upcomingIfVacation) {
        java.time.MonthDay md = java.time.MonthDay.from(date);
        if (!md.isBefore(SEM1_START) && !md.isAfter(SEM1_END)) return 1;
        if (!md.isBefore(SEM2_START) && !md.isAfter(SEM2_END)) return 2;
        if (!upcomingIfVacation) return 0;
        // MonthDay сравнивается сперва по месяцу, поэтому "isAfter(SEM1_END)" истинно и для
        // 31 декабря (зимние каникулы, впереди 2 семестр), и одновременно НЕ истинно для
        // июля-августа (месяц 7/8 < 12) — что нам и нужно для второй ветки ниже.
        return md.isAfter(SEM1_END) || md.isBefore(SEM2_START) ? 2 : 1;
    }

    /** Начало/конец семестра как конкретные даты учебного года {@code academicYear} (Sep1(Y)..Dec30(Y), Jan14(Y+1)..Jun30(Y+1)). */
    public static LocalDate semesterStart(int semester, int academicYear) {
        return semester == 2 ? LocalDate.of(academicYear + 1, 1, 14) : LocalDate.of(academicYear, 9, 1);
    }

    public static LocalDate semesterEnd(int semester, int academicYear) {
        return semester == 2 ? LocalDate.of(academicYear + 1, 6, 30) : LocalDate.of(academicYear, 12, 30);
    }

    /**
     * Точное число учебных недель в семестре {@code academicYear} по границам ТЗ — семестры
     * НЕ равны по длине (1 семестр ≈17 недель, 2 семестр ≈24 недели), поэтому раньше здесь
     * стояла общая для обоих константа {@link #WEEKS_PER_SEMESTER}=18 — теперь считается
     * точно по датам, а не оценочно.
     */
    public static int getSemesterWeeks(int semester, int academicYear) {
        LocalDate start = semesterStart(semester, academicYear);
        LocalDate end = semesterEnd(semester, academicYear);
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        return (int) Math.max(1, Math.round(days / 7.0));
    }

    /**
     * Сколько учебных недель в ОДНОМ семестре — грубая ОЦЕНКА (используется как запасной
     * вариант там, где неизвестен конкретный семестр/год; предпочтительно
     * {@link #getSemesterWeeks(int, int)} с точными границами по ТЗ).
     */
    public static final int WEEKS_PER_SEMESTER = 18;

    /** Публичная обёртка над semesterOf для внешних проверок ("к какому семестру относится дата") — 0, если каникулы. */
    public static int semesterOfDate(LocalDate date) {
        return semesterOf(date, false);
    }

}