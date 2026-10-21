package com.karyakina.schedule.util;

import com.karyakina.schedule.domain.Schedule;

import java.time.LocalDate;

/**
 * Единое правило «проходит ли строка расписания в эту дату». Раньше каждый сервис проверял это по-своему
 * (где-то забывали семестр, где-то activeFromWeek/activeUntilWeek), из-за чего осенние и весенние пары
 * смешивались, а сокращение темпа не влияло на реальные занятия.
 */
public final class ScheduleOccurrence {

    private ScheduleOccurrence() {
    }

    public static boolean occursOn(Schedule s, LocalDate date, int academicWeek, int dateSemester) {
        if (s.getDayOfWeek() != date.getDayOfWeek()) return false;
        if (s.getSemester() != null && !s.getSemester().equals(dateSemester)) return false;
        if (s.getAcademicWeek() != null && !s.getAcademicWeek().equals(academicWeek)) return false;
        if (s.getActiveFromWeek() != null && academicWeek < s.getActiveFromWeek()) return false;
        if (s.getActiveUntilWeek() != null && academicWeek > s.getActiveUntilWeek()) return false;
        return true;
    }

    public static boolean occursOn(Schedule s, LocalDate date, int academicYear) {
        return occursOn(s, date, AcademicYearUtil.academicWeek(date, academicYear),
                AcademicYearUtil.semesterOfDate(date));
    }

    /** Постоянная (еженедельная) строка, а не разовый перенос/доп. пара. */
    public static boolean isRecurring(Schedule s) {
        return s.getAcademicWeek() == null;
    }
}
