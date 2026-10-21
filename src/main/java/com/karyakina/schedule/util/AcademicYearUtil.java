package com.karyakina.schedule.util;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

public class AcademicYearUtil {

    private AcademicYearUtil() {}

    private static final Set<MonthDay> STATE_HOLIDAYS = Set.of(
            MonthDay.of(2, 23),
            MonthDay.of(3, 8),
            MonthDay.of(5, 1),
            MonthDay.of(5, 9),
            MonthDay.of(6, 12),
            MonthDay.of(11, 4)
    );

    public static boolean isHoliday(LocalDate date) {
        return STATE_HOLIDAYS.contains(MonthDay.from(date));
    }

    /**
     * Учебный год начинается 1 сентября: январь–июнь относятся к году, начавшемуся прошлой осенью.
     * Июль–август считаем уже предстоящим учебным годом (как и {@link #getCurrentSemester()}).
     */
    public static int[] getCurrentAcademicYear() {
        return new int[]{academicYearOf(LocalDate.now()), academicYearOf(LocalDate.now()) + 1};
    }

    public static int academicYearOf(LocalDate date) {
        return date.getMonthValue() >= 7 ? date.getYear() : date.getYear() - 1;
    }

    /** Номер учебной недели от недели, в которую попадает 1 сентября (понедельник — первый день). */
    public static int academicWeek(LocalDate date, int academicYear) {
        LocalDate start = LocalDate.of(academicYear, 9, 1);
        if (date.isBefore(start.with(java.time.DayOfWeek.MONDAY))) {
            start = LocalDate.of(academicYear - 1, 9, 1);
        }
        java.time.temporal.WeekFields iso = java.time.temporal.WeekFields.ISO;
        long weeks = java.time.temporal.ChronoUnit.WEEKS.between(
                start.with(iso.getFirstDayOfWeek()), date.with(iso.getFirstDayOfWeek()));
        return (int) weeks + 1;
    }

    public static boolean isWorkDay(LocalDate date) {
        java.time.DayOfWeek d = date.getDayOfWeek();
        return d != java.time.DayOfWeek.SATURDAY && d != java.time.DayOfWeek.SUNDAY;
    }

    /** Учебные дни (пн–пт, не каникулы, не праздники) в интервале включительно. */
    public static int teachingDays(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWorkDay(d) && !isVacation(d)) days++;
        }
        return days;
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
        return semesterOf(date, false) == 0 || isHoliday(date);
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

    /** Число учебных недель семестра: учебные дни без праздников, делённые на 5 дней недели. */
    public static int getSemesterWeeks(int semester, int academicYear) {
        LocalDate start = semesterStart(semester, academicYear);
        LocalDate end = semesterEnd(semester, academicYear);
        return (int) Math.max(1, Math.round(teachingDays(start, end) / 5.0));
    }

    public static final int WEEKS_PER_SEMESTER = 18;

    public static int semesterOfDate(LocalDate date) {
        return semesterOf(date, false);
    }

}