package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.StudyGroup;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.dto.ScheduleGenerationResultDto;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;

/**
 * MODULE: automatic balanced schedule generation.
 * Instead of first-fit greedy placement, every candidate slot for a pair is scored and
 * the best-scoring one wins:
 *   +50 discipline matches teacher specialization
 *   +20 even weekly load distribution (penalty for 6-in-a-row day)
 *   +15 minimizes teacher gaps ("windows") that day
 *   +10 matches teacher's preferred day/time
 *   +5  room capacity fit for the group
 * Hard constraints (never violated): teacher/group double-booking, max pairs/day,
 * planned-hours overrun without an "overload" flag is flagged as a conflict, not blocked.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class ScheduleGeneratorService {

    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;

    private static final DayOfWeek[] WORK_DAYS = {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
    };

    private static final LocalTime[][] TIME_SLOTS = {
            {LocalTime.of(8, 30), LocalTime.of(10, 0)},
            {LocalTime.of(10, 10), LocalTime.of(11, 40)},
            {LocalTime.of(11, 50), LocalTime.of(13, 20)},
            {LocalTime.of(13, 50), LocalTime.of(15, 20)},
            {LocalTime.of(15, 30), LocalTime.of(17, 0)},
            {LocalTime.of(17, 10), LocalTime.of(18, 40)},
    };

    private static final String[][] CLASSROOMS = {
            {"101", "20"}, {"102", "20"}, {"201", "30"}, {"202", "30"},
            {"301", "45"}, {"302", "45"}, {"401", "60"}, {"402", "60"},
            {"Aktoviy zal", "120"}, {"Lab. 1", "15"}, {"Lab. 2", "15"},
    };

    private static final int DEFAULT_MAX_PAIRS_PER_DAY = 4;
    private static final int APPROX_WEEKS_PER_YEAR = 36;
    private static final int MAX_COMFORTABLE_DAILY_PAIRS = 5;

    @Transactional
    public ScheduleGenerationResultDto generate(Integer academicYear, boolean persist) {
        List<TeacherLoad> allLoads = loadRepository.findByAcademicYear(academicYear);
        List<Schedule> existingSchedules = scheduleRepository.findByAcademicYear(academicYear);

        Set<Long> loadsWithSchedule = new HashSet<>();
        existingSchedules.forEach(s -> loadsWithSchedule.add(s.getTeacherLoad().getId()));

        List<TeacherLoad> toSchedule = allLoads.stream()
                .filter(l -> !loadsWithSchedule.contains(l.getId()))
                .sorted((a, b) -> Integer.compare(sessionsPerWeek(b), sessionsPerWeek(a)))
                .toList();

        State state = new State();
        for (Schedule s : existingSchedules) {
            state.markBusy(s);
        }

        List<Schedule> created = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        int unresolvedLoads = 0;

        for (TeacherLoad load : toSchedule) {
            int needed = sessionsPerWeek(load);
            int placedForLoad = 0;

            for (int session = 0; session < needed; session++) {
                Placement best = findBestPlacement(load, state);
                if (best != null) {
                    Schedule schedule = Schedule.builder()
                            .teacherLoad(load)
                            .dayOfWeek(WORK_DAYS[best.dayIdx])
                            .startTime(TIME_SLOTS[best.slotIdx][0])
                            .endTime(TIME_SLOTS[best.slotIdx][1])
                            .classroom(best.classroom)
                            .academicWeek(null)
                            .academicYear(load.getAcademicYear())
                            .build();
                    state.markBusy(schedule);
                    created.add(schedule);
                    placedForLoad++;
                } else {
                    conflicts.add(String.format(
                            "Ne udalos razmestit paru %d/%d: %s, %s, gruppa %s - net svobodnyh slotov " +
                            "bez narusheniya ogranicheniy",
                            session + 1, needed, load.getTeacher().getFullName(),
                            load.getDiscipline().getName(), load.getGroup().getName()));
                }
            }

            if (placedForLoad < needed) {
                unresolvedLoads++;
            }

            long approxWeeklyHours = needed * 2L;
            if (!Boolean.TRUE.equals(load.getOverload())
                    && approxWeeklyHours * APPROX_WEEKS_PER_YEAR > load.getPlannedHours() * 1.1) {
                conflicts.add(String.format(
                        "Prevyshenie planovoy nagruzki: %s / %s / %s - raschetno ~%d ch/god pri plane %d ch",
                        load.getTeacher().getFullName(), load.getDiscipline().getName(), load.getGroup().getName(),
                        approxWeeklyHours * APPROX_WEEKS_PER_YEAR, load.getPlannedHours()));
            }
        }

        for (TeacherLoad load : toSchedule) {
            long placedForThisLoad = created.stream().filter(s -> s.getTeacherLoad().getId().equals(load.getId())).count();
            int scheduledWeeklyHours = (int) (placedForThisLoad * 2);
            int scheduledYearlyHours = scheduledWeeklyHours * APPROX_WEEKS_PER_YEAR;
            int planned = load.getPlannedHours() != null ? load.getPlannedHours() : 0;
            if (planned > 0) {
                double deviation = Math.abs(scheduledYearlyHours - planned) / (double) planned;
                if (deviation > 0.05) {
                    conflicts.add(String.format(
                            "Otklonenie ot plana > 5%%: %s / %s / %s - raspisano ~%d ch/god pri plane %d ch",
                            load.getTeacher().getFullName(), load.getDiscipline().getName(),
                            load.getGroup().getName(), scheduledYearlyHours, planned));
                }
            }
        }

        if (persist && !created.isEmpty()) {
            scheduleRepository.saveAll(created);
        }

        return ScheduleGenerationResultDto.builder()
                .totalLoadsConsidered(toSchedule.size())
                .placedLessons(created.size())
                .unresolvedLoads(unresolvedLoads)
                .createdSchedules(created)
                .conflicts(conflicts)
                .build();
    }

    private static class Placement {
        int dayIdx;
        int slotIdx;
        String classroom;
        int score;
    }

    private Placement findBestPlacement(TeacherLoad load, State state) {
        Long teacherId = load.getTeacher().getId();
        Long groupId = load.getGroup().getId();
        int maxPerDay = load.getTeacher().getMaxPairsPerDay() != null
                ? load.getTeacher().getMaxPairsPerDay() : DEFAULT_MAX_PAIRS_PER_DAY;

        List<Integer> preferredDays = preferredDayIndexes(load);
        List<Integer> preferredSlots = preferredSlotIndexes(load);

        Placement best = null;

        for (int dayIdx = 0; dayIdx < WORK_DAYS.length; dayIdx++) {
            int dailyCount = state.dailyCount(teacherId, dayIdx);
            if (dailyCount >= maxPerDay) continue;

            for (int slotIdx = 0; slotIdx < TIME_SLOTS.length; slotIdx++) {
                if (state.isTeacherBusy(teacherId, dayIdx, slotIdx)) continue;
                if (state.isGroupBusy(groupId, dayIdx, slotIdx)) continue;

                String classroom = pickClassroom(load.getGroup(), state, dayIdx, slotIdx);
                if (classroom == null) continue;

                int score = scorePlacement(load, state, dayIdx, slotIdx, classroom, preferredDays, preferredSlots);

                if (best == null || score > best.score) {
                    best = new Placement();
                    best.dayIdx = dayIdx;
                    best.slotIdx = slotIdx;
                    best.classroom = classroom;
                    best.score = score;
                }
            }
        }
        return best;
    }

    private int scorePlacement(TeacherLoad load, State state, int dayIdx, int slotIdx, String classroom,
                                List<Integer> preferredDays, List<Integer> preferredSlots) {
        int score = 0;
        Long teacherId = load.getTeacher().getId();

        if (matchesSpecialization(load.getTeacher(), load.getDiscipline().getName())) {
            score += 50;
        }

        int dailyCountAfter = state.dailyCount(teacherId, dayIdx) + 1;
        int maxDailyAcrossWeek = state.maxDailyCount(teacherId);
        if (dailyCountAfter <= MAX_COMFORTABLE_DAILY_PAIRS) {
            int balanceBonus = Math.max(0, 20 - (dailyCountAfter - 1) * 4);
            score += balanceBonus;
        } else {
            score -= 30;
        }
        if (dailyCountAfter > maxDailyAcrossWeek) {
            score -= 5;
        }

        if (state.isAdjacentToExisting(teacherId, dayIdx, slotIdx)) {
            score += 15;
        }

        if (preferredDays.contains(dayIdx) && preferredSlots.contains(slotIdx)) {
            score += 10;
        } else if (preferredDays.contains(dayIdx) || preferredSlots.contains(slotIdx)) {
            score += 5;
        }

        score += roomFitScore(load.getGroup(), classroom);

        return score;
    }

    private boolean matchesSpecialization(Teacher teacher, String disciplineName) {
        if (teacher.getSpecialization() == null || teacher.getSpecialization().isBlank()) return false;
        String discNorm = disciplineName.toLowerCase(Locale.ROOT);
        for (String token : teacher.getSpecialization().split(",")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && (discNorm.contains(t) || t.contains(discNorm))) {
                return true;
            }
        }
        return false;
    }

    private String pickClassroom(StudyGroup group, State state, int dayIdx, int slotIdx) {
        int needed = group.getStudentCount() != null ? group.getStudentCount() : 0;
        String bestRoom = null;
        int bestCapacity = Integer.MAX_VALUE;

        for (String[] room : CLASSROOMS) {
            String name = room[0];
            int capacity = Integer.parseInt(room[1]);
            if (state.isRoomBusy(name, dayIdx, slotIdx)) continue;
            if (capacity < needed) continue;
            if (capacity < bestCapacity) {
                bestCapacity = capacity;
                bestRoom = name;
            }
        }
        if (bestRoom == null) {
            for (String[] room : CLASSROOMS) {
                if (!state.isRoomBusy(room[0], dayIdx, slotIdx)) {
                    return room[0];
                }
            }
        }
        return bestRoom;
    }

    private int roomFitScore(StudyGroup group, String classroom) {
        int needed = group.getStudentCount() != null ? group.getStudentCount() : 0;
        for (String[] room : CLASSROOMS) {
            if (room[0].equals(classroom)) {
                int capacity = Integer.parseInt(room[1]);
                if (needed == 0) return 2;
                double ratio = capacity / (double) needed;
                if (ratio >= 1.0 && ratio <= 1.3) return 5;
                if (ratio > 1.3 && ratio <= 2.0) return 3;
                return 1;
            }
        }
        return 0;
    }

    private int sessionsPerWeek(TeacherLoad load) {
        if (load.getHoursPerWeek() != null && load.getHoursPerWeek() > 0) {
            return Math.max(1, (int) Math.round(load.getHoursPerWeek() / 2.0));
        }
        int planned = load.getPlannedHours() != null ? load.getPlannedHours() : 0;
        double hoursPerWeek = planned / (double) APPROX_WEEKS_PER_YEAR;
        return Math.max(1, (int) Math.round(hoursPerWeek / 2.0));
    }

    private List<Integer> preferredDayIndexes(TeacherLoad load) {
        if (load.getPreferredDays() == null || load.getPreferredDays().isBlank()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (String token : load.getPreferredDays().split(",")) {
            try {
                DayOfWeek dow = DayOfWeek.valueOf(token.trim().toUpperCase(Locale.ROOT));
                for (int i = 0; i < WORK_DAYS.length; i++) {
                    if (WORK_DAYS[i] == dow) result.add(i);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    private List<Integer> preferredSlotIndexes(TeacherLoad load) {
        if (load.getPreferredTimeSlots() == null || load.getPreferredTimeSlots().isBlank()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (String token : load.getPreferredTimeSlots().split(",")) {
            try {
                int n = Integer.parseInt(token.trim());
                if (n >= 1 && n <= TIME_SLOTS.length) result.add(n - 1);
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static class State {
        final Map<Long, boolean[][]> teacherBusy = new HashMap<>();
        final Map<Long, boolean[][]> groupBusy = new HashMap<>();
        final Map<String, boolean[][]> roomBusy = new HashMap<>();
        final Map<Long, int[]> teacherDailyCount = new HashMap<>();

        void markBusy(Schedule s) {
            int dayIdx = dayIndex(s.getDayOfWeek());
            int slotIdx = slotIndex(s.getStartTime());
            Long teacherId = s.getTeacherLoad().getTeacher().getId();
            Long groupId = s.getTeacherLoad().getGroup().getId();

            if (dayIdx < 0) return;

            boolean[][] tBusy = teacherBusy.computeIfAbsent(teacherId, k -> new boolean[WORK_DAYS.length][TIME_SLOTS.length]);
            boolean[][] gBusy = groupBusy.computeIfAbsent(groupId, k -> new boolean[WORK_DAYS.length][TIME_SLOTS.length]);
            int[] daily = teacherDailyCount.computeIfAbsent(teacherId, k -> new int[WORK_DAYS.length]);

            if (slotIdx >= 0) {
                tBusy[dayIdx][slotIdx] = true;
                gBusy[dayIdx][slotIdx] = true;
                if (s.getClassroom() != null) {
                    boolean[][] rBusy = roomBusy.computeIfAbsent(s.getClassroom(), k -> new boolean[WORK_DAYS.length][TIME_SLOTS.length]);
                    rBusy[dayIdx][slotIdx] = true;
                }
            } else {
                Arrays.fill(tBusy[dayIdx], true);
                Arrays.fill(gBusy[dayIdx], true);
            }
            daily[dayIdx]++;
        }

        boolean isTeacherBusy(Long teacherId, int day, int slot) {
            boolean[][] arr = teacherBusy.get(teacherId);
            return arr != null && arr[day][slot];
        }

        boolean isGroupBusy(Long groupId, int day, int slot) {
            boolean[][] arr = groupBusy.get(groupId);
            return arr != null && arr[day][slot];
        }

        boolean isRoomBusy(String room, int day, int slot) {
            boolean[][] arr = roomBusy.get(room);
            return arr != null && arr[day][slot];
        }

        int dailyCount(Long teacherId, int day) {
            int[] arr = teacherDailyCount.get(teacherId);
            return arr == null ? 0 : arr[day];
        }

        int maxDailyCount(Long teacherId) {
            int[] arr = teacherDailyCount.get(teacherId);
            if (arr == null) return 0;
            int max = 0;
            for (int v : arr) max = Math.max(max, v);
            return max;
        }

        boolean isAdjacentToExisting(Long teacherId, int day, int slot) {
            boolean[][] arr = teacherBusy.get(teacherId);
            if (arr == null) return false;
            boolean before = slot > 0 && arr[day][slot - 1];
            boolean after = slot < TIME_SLOTS.length - 1 && arr[day][slot + 1];
            return before || after;
        }

        int dayIndex(DayOfWeek dow) {
            for (int i = 0; i < WORK_DAYS.length; i++) if (WORK_DAYS[i] == dow) return i;
            return -1;
        }

        int slotIndex(LocalTime start) {
            for (int i = 0; i < TIME_SLOTS.length; i++) if (TIME_SLOTS[i][0].equals(start)) return i;
            return -1;
        }
    }
}
