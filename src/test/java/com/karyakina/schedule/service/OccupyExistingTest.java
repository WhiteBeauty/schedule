package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Discipline;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.StudyGroup;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.service.generator.OccupancyIndex;
import com.karyakina.schedule.service.generator.SolverConfig;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OccupyExistingTest {

    private final ScheduleGeneratorService service = new ScheduleGeneratorService(
            null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void oneOffEventDoesNotConsumeGroupWeeklyBudget() {
        StudyGroup group = StudyGroup.builder().id(1L).name("G1").build();
        Teacher teacher = Teacher.builder().id(1L).fullName("T1").build();
        Discipline discipline = Discipline.builder().id(1L).name("Exam subject").build();
        TeacherLoad load = TeacherLoad.builder().id(1L).group(group).teacher(teacher).discipline(discipline).build();

        Schedule exam = Schedule.builder()
                .teacherLoad(load)
                .dayOfWeek(DayOfWeek.TUESDAY)
                .startTime(GenerationGrid.start(0))
                .endTime(GenerationGrid.end(0))
                .classroom("101")
                .academicWeek(5)
                .academicYear(2026)
                .build();

        OccupancyIndex index = new OccupancyIndex(SolverConfig.defaults());
        service.occupyExisting(index, List.of(exam));

        int tuesdayIdx = GenerationGrid.dayIndex(DayOfWeek.TUESDAY);
        assertEquals(0, index.groupDayCount(1L, tuesdayIdx));
        assertEquals(0, index.weekPairsGroup(1L));
        assertFalse(index.groupTimeline(1L)[GenerationGrid.flat(tuesdayIdx, 0)]);
    }

    @Test
    void recurringLessonStillConsumesGroupWeeklyBudget() {
        StudyGroup group = StudyGroup.builder().id(1L).name("G1").build();
        Teacher teacher = Teacher.builder().id(1L).fullName("T1").build();
        Discipline discipline = Discipline.builder().id(1L).name("Regular subject").build();
        TeacherLoad load = TeacherLoad.builder().id(1L).group(group).teacher(teacher).discipline(discipline).build();

        Schedule regular = Schedule.builder()
                .teacherLoad(load)
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(GenerationGrid.start(0))
                .endTime(GenerationGrid.end(0))
                .classroom("101")
                .academicWeek(null)
                .academicYear(2026)
                .build();

        OccupancyIndex index = new OccupancyIndex(SolverConfig.defaults());
        service.occupyExisting(index, List.of(regular));

        int mondayIdx = GenerationGrid.dayIndex(DayOfWeek.MONDAY);
        assertEquals(1, index.groupDayCount(1L, mondayIdx));
        assertEquals(1, index.weekPairsGroup(1L));
        assertTrue(index.groupTimeline(1L)[GenerationGrid.flat(mondayIdx, 0)]);
    }
}
