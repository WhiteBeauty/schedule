package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Schedule;
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
 * МОДУЛЬ АВТОМАТИЧЕСКОГО СОСТАВЛЕНИЯ РАСПИСАНИЯ.
 *
 * Жадный алгоритм с приоритезацией "самых стеснённых" нагрузок (больше сессий в неделю —
 * раньше распределяется). Учитывает жёсткие ограничения:
 *   1) преподаватель не может вести две пары одновременно;
 *   2) группа не может быть на двух парах одновременно;
 *   3) максимум пар в день на преподавателя (Teacher.maxPairsPerDay, по умолчанию 4);
 *   4) превышение плановой годовой нагрузки — только с флагом "переработка" (мягкое
 *      предупреждение в конфликтах, не блокирует размещение).
 *
 * Берёт в работу только те TeacherLoad, у которых ещё нет ни одной записи в Schedule
 * (чтобы не трогать то, что методист уже расставил вручную).
 * Результат можно сначала получить как черновик (persist=false), а затем применить
 * (persist=true) — тот же алгоритм детерминирован при неизменных исходных данных.
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

    /** Стандартная сетка пар колледжа/вуза: номер -> [начало, конец]. */
    private static final LocalTime[][] TIME_SLOTS = {
            {LocalTime.of(8, 30), LocalTime.of(10, 0)},
            {LocalTime.of(10, 10), LocalTime.of(11, 40)},
            {LocalTime.of(11, 50), LocalTime.of(13, 20)},
            {LocalTime.of(13, 50), LocalTime.of(15, 20)},
            {LocalTime.of(15, 30), LocalTime.of(17, 0)},
            {LocalTime.of(17, 10), LocalTime.of(18, 40)},
    };

    private static final int DEFAULT_MAX_PAIRS_PER_DAY = 4;
    private static final int APPROX_WEEKS_PER_YEAR = 36;

    @Transactional
    public ScheduleGenerationResultDto generate(Integer academicYear, boolean persist) {
        List<TeacherLoad> allLoads = loadRepository.findByAcademicYear(academicYear);
        List<Schedule> existingSchedules = scheduleRepository.findByAcademicYear(academicYear);

        Set<Long> loadsWithSchedule = new HashSet<>();
        existingSchedules.forEach(s -> loadsWithSchedule.add(s.getTeacherLoad().getId()));

        List<TeacherLoad> toSchedule = allLoads.stream()
                .filter(l -> !loadsWithSchedule.contains(l.getId()))
                .sorted((a, b) -> Integer.compare(sessionsPerWeek(b), sessionsPerWeek(a))) // самые загруженные первыми
                .toList();

        // busy[teacherId or groupId][day][slotIndex]
        Map<Long, boolean[][]> teacherBusy = new HashMap<>();
        Map<Long, boolean[][]> groupBusy = new HashMap<>();
        Map<Long, int[]> teacherDailyCount = new HashMap<>(); // [dayIndex] -> count

        // Учитываем уже существующие пары, чтобы не размещать новые поверх них
        for (Schedule s : existingSchedules) {
            markBusy(teacherBusy, s.getTeacherLoad().getTeacher().getId(), s);
            markBusy(groupBusy, s.getTeacherLoad().getGroup().getId(), s);
            incrementDaily(teacherDailyCount, s.getTeacherLoad().getTeacher().getId(), s.getDayOfWeek());
        }

        List<Schedule> created = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        int unresolvedLoads = 0;

        for (TeacherLoad load : toSchedule) {
            int needed = sessionsPerWeek(load);
            int placedForLoad = 0;

            List<Integer> preferredDayIdx = preferredDayIndexes(load);
            List<Integer> preferredSlotIdx = preferredSlotIndexes(load);

            for (int session = 0; session < needed; session++) {
                boolean placed = tryPlace(load, preferredDayIdx, preferredSlotIdx,
                        teacherBusy, groupBusy, teacherDailyCount, created);
                if (!placed) {
                    // повторная попытка без учёта предпочтений (используем все дни/слоты)
                    placed = tryPlace(load, allDayIndexes(), allSlotIndexes(),
                            teacherBusy, groupBusy, teacherDailyCount, created);
                }
                if (placed) {
                    placedForLoad++;
                } else {
                    conflicts.add(String.format(
                            "Не удалось разместить пару %d/%d: %s, %s, группа %s — нет свободных слотов " +
                            "без нарушения ограничений (двойное занятие/лимит пар в день)",
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
                        "Превышение плановой нагрузки: %s / %s / %s — расчётно ~%d ч/год при плане %d ч " +
                        "(требуется флаг «переработка» или пересмотр часов в неделю)",
                        load.getTeacher().getFullName(), load.getDiscipline().getName(), load.getGroup().getName(),
                        approxWeeklyHours * APPROX_WEEKS_PER_YEAR, load.getPlannedHours()));
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

    private boolean tryPlace(TeacherLoad load, List<Integer> dayIndexes, List<Integer> slotIndexes,
                              Map<Long, boolean[][]> teacherBusy, Map<Long, boolean[][]> groupBusy,
                              Map<Long, int[]> teacherDailyCount, List<Schedule> created) {
        Long teacherId = load.getTeacher().getId();
        Long groupId = load.getGroup().getId();
        int maxPerDay = load.getTeacher().getMaxPairsPerDay() != null
                ? load.getTeacher().getMaxPairsPerDay() : DEFAULT_MAX_PAIRS_PER_DAY;

        for (int dayIdx : dayIndexes) {
            int[] dailyCount = teacherDailyCount.computeIfAbsent(teacherId, k -> new int[WORK_DAYS.length]);
            if (dailyCount[dayIdx] >= maxPerDay) continue;

            for (int slotIdx : slotIndexes) {
                boolean[][] tBusy = teacherBusy.computeIfAbsent(teacherId, k -> new boolean[WORK_DAYS.length][TIME_SLOTS.length]);
                boolean[][] gBusy = groupBusy.computeIfAbsent(groupId, k -> new boolean[WORK_DAYS.length][TIME_SLOTS.length]);

                if (tBusy[dayIdx][slotIdx] || gBusy[dayIdx][slotIdx]) continue;

                // Место найдено — размещаем
                Schedule schedule = Schedule.builder()
                        .teacherLoad(load)
                        .dayOfWeek(WORK_DAYS[dayIdx])
                        .startTime(TIME_SLOTS[slotIdx][0])
                        .endTime(TIME_SLOTS[slotIdx][1])
                        .classroom("уточняется")
                        .academicWeek(null)
                        .academicYear(load.getAcademicYear())
                        .build();

                tBusy[dayIdx][slotIdx] = true;
                gBusy[dayIdx][slotIdx] = true;
                dailyCount[dayIdx]++;
                created.add(schedule);
                return true;
            }
        }
        return false;
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
        if (load.getPreferredDays() == null || load.getPreferredDays().isBlank()) return allDayIndexes();
        List<Integer> result = new ArrayList<>();
        for (String token : load.getPreferredDays().split(",")) {
            try {
                DayOfWeek dow = DayOfWeek.valueOf(token.trim().toUpperCase(Locale.ROOT));
                for (int i = 0; i < WORK_DAYS.length; i++) {
                    if (WORK_DAYS[i] == dow) result.add(i);
                }
            } catch (IllegalArgumentException ignored) {
                // неизвестный токен — игнорируем, попробуем все дни в резервном проходе
            }
        }
        return result.isEmpty() ? allDayIndexes() : result;
    }

    private List<Integer> preferredSlotIndexes(TeacherLoad load) {
        if (load.getPreferredTimeSlots() == null || load.getPreferredTimeSlots().isBlank()) return allSlotIndexes();
        List<Integer> result = new ArrayList<>();
        for (String token : load.getPreferredTimeSlots().split(",")) {
            try {
                int n = Integer.parseInt(token.trim());
                if (n >= 1 && n <= TIME_SLOTS.length) result.add(n - 1);
            } catch (NumberFormatException ignored) {
                // игнорируем
            }
        }
        return result.isEmpty() ? allSlotIndexes() : result;
    }

    private List<Integer> allDayIndexes() {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < WORK_DAYS.length; i++) all.add(i);
        return all;
    }

    private List<Integer> allSlotIndexes() {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < TIME_SLOTS.length; i++) all.add(i);
        return all;
    }

    private void markBusy(Map<Long, boolean[][]> busyMap, Long key, Schedule s) {
        int dayIdx = -1;
        for (int i = 0; i < WORK_DAYS.length; i++) if (WORK_DAYS[i] == s.getDayOfWeek()) dayIdx = i;
        if (dayIdx < 0) return; // воскресенье и т.п. — вне сетки генератора, не учитываем
        int slotIdx = -1;
        for (int i = 0; i < TIME_SLOTS.length; i++) {
            if (TIME_SLOTS[i][0].equals(s.getStartTime())) slotIdx = i;
        }
        boolean[][] busy = busyMap.computeIfAbsent(key, k -> new boolean[WORK_DAYS.length][TIME_SLOTS.length]);
        if (slotIdx >= 0) {
            busy[dayIdx][slotIdx] = true;
        } else {
            // пара по нестандартной сетке времени — на всякий случай блокируем весь день для этого дня
            Arrays.fill(busy[dayIdx], true);
        }
    }

    private void incrementDaily(Map<Long, int[]> dailyMap, Long teacherId, DayOfWeek dow) {
        int dayIdx = -1;
        for (int i = 0; i < WORK_DAYS.length; i++) if (WORK_DAYS[i] == dow) dayIdx = i;
        if (dayIdx < 0) return;
        int[] counts = dailyMap.computeIfAbsent(teacherId, k -> new int[WORK_DAYS.length]);
        counts[dayIdx]++;
    }
}
