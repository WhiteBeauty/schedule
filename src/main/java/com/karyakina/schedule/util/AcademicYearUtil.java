package com.karyakina.schedule.util;

import java.time.LocalDate;

public class AcademicYearUtil {

    private AcademicYearUtil() {}

    public static int[] getCurrentAcademicYear() {
        int year = LocalDate.now().getYear();
        return new int[]{year, year + 1};
    }

    public static String getAcademicYearString() {
        int[] years = getCurrentAcademicYear();
        return years[0] + "-" + years[1];
    }

    public static int getCurrentAcademicYearStart() {
        return getCurrentAcademicYear()[0];
    }

    public static int getCurrentSemester() {
        return semesterOf(LocalDate.now(), true);
    }

    public static final java.time.MonthDay SEM1_START = java.time.MonthDay.of(9, 1);
    public static final java.time.MonthDay SEM1_END = java.time.MonthDay.of(12, 30);
    public static final java.time.MonthDay SEM2_START = java.time.MonthDay.of(1, 14);
    public static final java.time.MonthDay SEM2_END = java.time.MonthDay.of(6, 30);

    public static boolean isVacation(LocalDate date) {
        return semesterOf(date, false) == 0;
    }

    private static int semesterOf(LocalDate date, boolean upcomingIfVacation) {
        java.time.MonthDay md = java.time.MonthDay.from(date);
        if (!md.isBefore(SEM1_START) && !md.isAfter(SEM1_END)) return 1;
        if (!md.isBefore(SEM2_START) && !md.isAfter(SEM2_END)) return 2;
        if (!upcomingIfVacation) return 0;
        return md.isAfter(SEM1_END) || md.isBefore(SEM2_START) ? 2 : 1;
    }

    public static LocalDate semesterStart(int semester, int academicYear) {
        return semester == 2 ? LocalDate.of(academicYear + 1, 1, 14) : LocalDate.of(academicYear, 9, 1);
    }

    public static LocalDate semesterEnd(int semester, int academicYear) {
        return semester == 2 ? LocalDate.of(academicYear + 1, 6, 30) : LocalDate.of(academicYear, 12, 30);
    }

    public static int getSemesterWeeks(int semester, int academicYear) {
        LocalDate start = semesterStart(semester, academicYear);
        LocalDate end = semesterEnd(semester, academicYear);
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        return (int) Math.max(1, Math.round(days / 7.0));
    }

    public static final int WEEKS_PER_SEMESTER = 18;

    public static int semesterOfDate(LocalDate date) {
        return semesterOf(date, false);
    }

}