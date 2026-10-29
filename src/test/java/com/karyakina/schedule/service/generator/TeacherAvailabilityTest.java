package com.karyakina.schedule.service.generator;

import com.karyakina.schedule.domain.Teacher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeacherAvailabilityTest {

    private static boolean[] days(int... used) {
        boolean[] result = new boolean[GenerationGrid.days()];
        for (int d : used) result[d] = true;
        return result;
    }

    @Test
    void mondayOrFridayButNotBoth() {
        Teacher teacher = Teacher.builder().optionalDays("1,5").maxDaysPerWeek(4).forbiddenPairs("1").build();
        TeacherAvailability a = TeacherAvailability.of(teacher);

        assertTrue(a.canUseDay(days(1, 2, 3), 0));
        assertFalse(a.canUseDay(days(0, 1, 2), 4));
        assertTrue(a.canUseDay(days(0, 1), 2));
        assertFalse(a.canUseDay(days(0, 1, 2, 3), 4));
        assertTrue(a.pairForbidden(0));
        assertEquals(16, a.slotsPerWeek());
    }

    @Test
    void threeDaysOfAnyFive() {
        TeacherAvailability a = TeacherAvailability.of(Teacher.builder().maxDaysPerWeek(3).build());

        assertTrue(a.canUseDay(days(0, 2), 4));
        assertFalse(a.canUseDay(days(0, 2, 4), 1));
        assertTrue(a.canUseDay(days(0, 2, 4), 2));
    }

    @Test
    void forbiddenAndUndesiredPairs() {
        TeacherAvailability a = TeacherAvailability.of(Teacher.builder().forbiddenPairs("4,5").undesiredPairs("4,3").build());

        assertTrue(a.pairForbidden(3));
        assertTrue(a.pairForbidden(4));
        assertFalse(a.pairUndesired(3));
        assertTrue(a.pairUndesired(2));
        assertEquals(15, a.slotsPerWeek());
    }

    @Test
    void noLimitsMeansEverythingAllowed() {
        TeacherAvailability a = TeacherAvailability.of(Teacher.builder().fullName("Без ограничений").build());

        assertTrue(a.isEmpty());
        assertTrue(a.canUseDay(days(0, 1, 2, 3), 4));
    }
}
