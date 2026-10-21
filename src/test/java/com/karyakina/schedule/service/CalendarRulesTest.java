package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.util.AcademicYearUtil;
import com.karyakina.schedule.util.ScheduleOccurrence;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarRulesTest {

    @Test
    void springBelongsToAcademicYearStartedLastAutumn() {
        assertEquals(2026, AcademicYearUtil.academicYearOf(LocalDate.of(2027, 3, 15)));
        assertEquals(2026, AcademicYearUtil.academicYearOf(LocalDate.of(2026, 10, 21)));
        assertEquals(2027, AcademicYearUtil.academicYearOf(LocalDate.of(2027, 8, 20)));
    }

    @Test
    void academicWeekStartsOnMondayOfSeptemberFirstWeek() {
        // 1 сентября 2026 — вторник; понедельник 31 августа — тоже 1-я неделя.
        assertEquals(1, AcademicYearUtil.academicWeek(LocalDate.of(2026, 8, 31), 2026));
        assertEquals(1, AcademicYearUtil.academicWeek(LocalDate.of(2026, 9, 6), 2026));
        assertEquals(2, AcademicYearUtil.academicWeek(LocalDate.of(2026, 9, 7), 2026));
        assertTrue(AcademicYearUtil.academicWeek(LocalDate.of(2027, 3, 1), 2026) > 20);
    }

    @Test
    void semesterWeeksSkipHolidays() {
        assertEquals(17, AcademicYearUtil.getSemesterWeeks(1, 2026));
        assertEquals(24, AcademicYearUtil.getSemesterWeeks(2, 2026));
    }

    @Test
    void weekNumberMapsToSemester() {
        assertEquals(1, ScheduleService.semesterOfWeek(5, 2026));
        assertEquals(2, ScheduleService.semesterOfWeek(30, 2026));
        // неделя новогодних каникул относится к весне (ближайший семестр)
        assertEquals(2, ScheduleService.semesterOfWeek(19, 2026));
    }

    @Test
    void occurrenceRespectsSemesterAndActiveWeeks() {
        Schedule autumn = Schedule.builder().dayOfWeek(DayOfWeek.MONDAY).startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(9, 35)).semester(1).activeUntilWeek(10).build();
        LocalDate octMonday = LocalDate.of(2026, 10, 5);
        LocalDate decMonday = LocalDate.of(2026, 12, 7);
        LocalDate marMonday = LocalDate.of(2027, 3, 1);
        assertTrue(ScheduleOccurrence.occursOn(autumn, octMonday, 2026));
        assertFalse(ScheduleOccurrence.occursOn(autumn, decMonday, 2026), "после activeUntilWeek пары нет");
        assertFalse(ScheduleOccurrence.occursOn(autumn, marMonday, 2026), "осенняя пара не идёт весной");
    }

    @Test
    void windowRule() {
        TreeSet<Integer> day = new TreeSet<>(List.of(1, 2));
        assertFalse(CalendarSlotFinder.Calendar.createsWindow(day, 0), "вплотную перед первой парой");
        assertFalse(CalendarSlotFinder.Calendar.createsWindow(day, 3), "вплотную после последней");
        assertTrue(CalendarSlotFinder.Calendar.createsWindow(day, 4));
        assertFalse(CalendarSlotFinder.Calendar.createsWindow(new TreeSet<>(List.of(0, 2)), 1), "закрывает окно");
        assertFalse(CalendarSlotFinder.Calendar.createsWindow(new TreeSet<>(), 3), "пустой день");
    }
}
