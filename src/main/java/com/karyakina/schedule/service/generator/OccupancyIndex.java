package com.karyakina.schedule.service.generator;

import java.util.HashMap;
import java.util.Map;

/**
 * Занятость ресурсов и проверка ЖЁСТКИХ ограничений. Всё на плоских массивах
 * длиной {@code дни * пары}, поэтому проверка одного варианта — несколько чтений массива.
 *
 * <p>Жёсткие ограничения, которые здесь никогда не нарушаются:
 * <ol>
 *   <li>преподаватель не ведёт две пары одновременно;</li>
 *   <li>группа не находится на двух парах одновременно;</li>
 *   <li>аудитория не занята двумя группами в одном слоте;</li>
 *   <li>недельная нагрузка преподавателя ≤ лимита (36 ч = 18 пар);</li>
 *   <li>пар в неделю у группы ≤ лимита (по ТЗ — 18);</li>
 *   <li>пар в день ≤ лимита у преподавателя и у группы;</li>
 *   <li>не более {@code maxSameSubjectInRow} одинаковых пар подряд (по умолчанию 3+ запрещены);</li>
 *   <li>не более {@code maxSameSubjectPerDay} пар одной дисциплины в день;</li>
 *   <li>обед и другие заблокированные слоты группы не занимаются;</li>
 *   <li>вместимость аудитории не меньше численности группы.</li>
 * </ol>
 */
public final class OccupancyIndex {

    public enum Violation {
        NONE,
        GROUP_BUSY,
        TEACHER_BUSY,
        ROOM_BUSY,
        GROUP_SLOT_BLOCKED,
        GROUP_DAY_LIMIT,
        GROUP_WEEK_LIMIT,
        TEACHER_DAY_LIMIT,
        TEACHER_WEEK_LIMIT,
        ROOM_CAPACITY,
        SUBJECT_ROW_LIMIT,
        SUBJECT_DAY_LIMIT
    }

    private final SolverConfig config;
    private final int slots = GenerationGrid.slotCount();
    private final int pairsPerDay = GenerationGrid.pairsPerDay();

    private final Map<Long, boolean[]> groupBusy = new HashMap<>();
    private final Map<Long, boolean[]> teacherBusy = new HashMap<>();
    private final Map<String, boolean[]> roomBusy = new HashMap<>();
    private final Map<Long, long[]> groupDisciplineAt = new HashMap<>();
    private final Map<Long, long[]> groupTeacherAt = new HashMap<>();
    private final Map<Long, String[]> groupRoomAt = new HashMap<>();
    private final Map<Long, int[]> groupDayCount = new HashMap<>();
    private final Map<Long, int[]> teacherDayCount = new HashMap<>();
    private final Map<Long, Integer> teacherWeekPairs = new HashMap<>();
    private final Map<Long, Integer> groupWeekPairs = new HashMap<>();
    private final Map<Long, SolverResult.PlacedPair[]> groupPairAt = new HashMap<>();

    public OccupancyIndex(SolverConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------------ проверка

    public Violation check(SolverInput.Demand demand,
                           SolverInput.GroupRef group,
                           SolverInput.TeacherRef teacher,
                           GenerationGrid.Room room,
                           int dayIdx,
                           int pairIdx) {
        int flat = GenerationGrid.flat(dayIdx, pairIdx);
        if (flat < 0 || flat >= slots) {
            return Violation.GROUP_SLOT_BLOCKED;
        }
        if (group.blockedSlots().contains(flat)) {
            return Violation.GROUP_SLOT_BLOCKED;
        }
        if (busy(groupBusy, group.id(), flat)) {
            return Violation.GROUP_BUSY;
        }
        if (teacher != null && busy(teacherBusy, teacher.id(), flat)) {
            return Violation.TEACHER_BUSY;
        }
        if (room != null && roomBusy(room.name(), flat)) {
            return Violation.ROOM_BUSY;
        }
        if (room != null && room.capacity() > 0 && group.studentCount() > room.capacity()) {
            return Violation.ROOM_CAPACITY;
        }
        if (dayCount(groupDayCount, group.id(), dayIdx) >= group.maxPairsPerDay()) {
            return Violation.GROUP_DAY_LIMIT;
        }
        if (group.maxWeeklyPairs() > 0 && weekPairsGroup(group.id()) >= group.maxWeeklyPairs()) {
            return Violation.GROUP_WEEK_LIMIT;
        }
        if (teacher != null && dayCount(teacherDayCount, teacher.id(), dayIdx) >= teacher.maxPairsPerDay()) {
            return Violation.TEACHER_DAY_LIMIT;
        }
        if (teacher != null && weekPairs(teacher.id()) >= teacher.maxWeeklyPairs()) {
            return Violation.TEACHER_WEEK_LIMIT;
        }
        if (subjectRunLength(group.id(), dayIdx, pairIdx, demand.disciplineId()) > config.maxSameSubjectInRow()) {
            return Violation.SUBJECT_ROW_LIMIT;
        }
        if (subjectDayCount(group.id(), dayIdx, demand.disciplineId()) >= demand.perDayLimit(config)) {
            return Violation.SUBJECT_DAY_LIMIT;
        }
        return Violation.NONE;
    }

    /** Длина серии одинаковой дисциплины, которая получится после постановки в этот слот. */
    private int subjectRunLength(long groupId, int dayIdx, int pairIdx, long disciplineId) {
        long[] line = groupDisciplineAt.get(groupId);
        if (line == null) {
            return 1;
        }
        int run = 1;
        for (int p = pairIdx - 1; p >= 0; p--) {
            if (line[GenerationGrid.flat(dayIdx, p)] == disciplineId) {
                run++;
            } else {
                break;
            }
        }
        for (int p = pairIdx + 1; p < pairsPerDay; p++) {
            if (line[GenerationGrid.flat(dayIdx, p)] == disciplineId) {
                run++;
            } else {
                break;
            }
        }
        return run;
    }

    public int subjectDayCount(long groupId, int dayIdx, long disciplineId) {
        long[] line = groupDisciplineAt.get(groupId);
        if (line == null) {
            return 0;
        }
        int count = 0;
        for (int p = 0; p < pairsPerDay; p++) {
            if (line[GenerationGrid.flat(dayIdx, p)] == disciplineId) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ изменение состояния

    public void place(SolverResult.PlacedPair pair) {
        int flat = pair.flat();
        array(groupBusy, pair.groupId())[flat] = true;
        array(teacherBusy, pair.teacherId())[flat] = true;
        roomArray(pair.room())[flat] = true;
        longArray(groupDisciplineAt, pair.groupId())[flat] = pair.disciplineId();
        longArray(groupTeacherAt, pair.groupId())[flat] = pair.teacherId();
        stringArray(pair.groupId())[flat] = pair.room();
        intArray(groupDayCount, pair.groupId())[pair.dayIndex()]++;
        intArray(teacherDayCount, pair.teacherId())[pair.dayIndex()]++;
        teacherWeekPairs.merge(pair.teacherId(), 1, Integer::sum);
        groupWeekPairs.merge(pair.groupId(), 1, Integer::sum);
        pairArray(pair.groupId())[flat] = pair;
    }

    public void remove(SolverResult.PlacedPair pair) {
        int flat = pair.flat();
        array(groupBusy, pair.groupId())[flat] = false;
        array(teacherBusy, pair.teacherId())[flat] = false;
        roomArray(pair.room())[flat] = false;
        longArray(groupDisciplineAt, pair.groupId())[flat] = 0L;
        longArray(groupTeacherAt, pair.groupId())[flat] = 0L;
        stringArray(pair.groupId())[flat] = null;
        int[] groupDays = intArray(groupDayCount, pair.groupId());
        groupDays[pair.dayIndex()] = Math.max(0, groupDays[pair.dayIndex()] - 1);
        int[] teacherDays = intArray(teacherDayCount, pair.teacherId());
        teacherDays[pair.dayIndex()] = Math.max(0, teacherDays[pair.dayIndex()] - 1);
        teacherWeekPairs.merge(pair.teacherId(), -1, (a, b) -> Math.max(0, a + b));
        groupWeekPairs.merge(pair.groupId(), -1, (a, b) -> Math.max(0, a + b));
        pairArray(pair.groupId())[flat] = null;
    }

    /**
     * Пометить слот занятым «извне»: уже сохранённые в БД пары, которые перегенерировать
     * не нужно, но которые реально занимают преподавателя, группу и аудиторию.
     */
    public void occupyExternal(Long groupId, Long teacherId, String room, int flat) {
        if (flat < 0 || flat >= slots) {
            return;
        }
        int dayIdx = GenerationGrid.dayOf(flat);
        if (groupId != null) {
            array(groupBusy, groupId)[flat] = true;
            intArray(groupDayCount, groupId)[dayIdx]++;
            groupWeekPairs.merge(groupId, 1, Integer::sum);
        }
        if (teacherId != null) {
            array(teacherBusy, teacherId)[flat] = true;
            intArray(teacherDayCount, teacherId)[dayIdx]++;
            teacherWeekPairs.merge(teacherId, 1, Integer::sum);
        }
        if (room != null && !room.isBlank()) {
            roomArray(room)[flat] = true;
        }
    }

    /** Дисциплина и преподаватель уже стоящей пары — чтобы учитывать «3 подряд» с учётом старых пар. */
    public void occupyExternalContent(Long groupId, Long teacherId, Long disciplineId, int flat) {
        if (groupId == null || flat < 0 || flat >= slots) {
            return;
        }
        if (disciplineId != null) {
            longArray(groupDisciplineAt, groupId)[flat] = disciplineId;
        }
        if (teacherId != null) {
            longArray(groupTeacherAt, groupId)[flat] = teacherId;
        }
    }

    // ------------------------------------------------------------------ чтение

    public boolean[] groupTimeline(long groupId) {
        return array(groupBusy, groupId);
    }

    public boolean[] teacherTimeline(long teacherId) {
        return array(teacherBusy, teacherId);
    }

    public long[] groupDisciplines(long groupId) {
        return longArray(groupDisciplineAt, groupId);
    }

    public long[] groupTeachers(long groupId) {
        return longArray(groupTeacherAt, groupId);
    }

    public String[] groupRooms(long groupId) {
        return stringArray(groupId);
    }

    public int groupDayCount(long groupId, int dayIdx) {
        return dayCount(groupDayCount, groupId, dayIdx);
    }

    public int weekPairs(long teacherId) {
        return teacherWeekPairs.getOrDefault(teacherId, 0);
    }

    public int weekPairsGroup(long groupId) {
        return groupWeekPairs.getOrDefault(groupId, 0);
    }

    /** Пара, занимающая слот у группы — нужна для «выталкивания» при перестановках. */
    public SolverResult.PlacedPair pairAt(long groupId, int flat) {
        SolverResult.PlacedPair[] arr = groupPairAt.get(groupId);
        return arr == null ? null : arr[flat];
    }

    // ------------------------------------------------------------------ утилиты

    private boolean busy(Map<Long, boolean[]> map, long id, int flat) {
        boolean[] arr = map.get(id);
        return arr != null && arr[flat];
    }

    private boolean roomBusy(String room, int flat) {
        boolean[] arr = roomBusy.get(room);
        return arr != null && arr[flat];
    }

    private int dayCount(Map<Long, int[]> map, long id, int dayIdx) {
        int[] arr = map.get(id);
        return arr == null ? 0 : arr[dayIdx];
    }

    private boolean[] array(Map<Long, boolean[]> map, long id) {
        return map.computeIfAbsent(id, k -> new boolean[slots]);
    }

    private boolean[] roomArray(String room) {
        return roomBusy.computeIfAbsent(room, k -> new boolean[slots]);
    }

    private long[] longArray(Map<Long, long[]> map, long id) {
        return map.computeIfAbsent(id, k -> new long[slots]);
    }

    private int[] intArray(Map<Long, int[]> map, long id) {
        return map.computeIfAbsent(id, k -> new int[GenerationGrid.days()]);
    }

    private String[] stringArray(long groupId) {
        return groupRoomAt.computeIfAbsent(groupId, k -> new String[slots]);
    }

    private SolverResult.PlacedPair[] pairArray(long groupId) {
        return groupPairAt.computeIfAbsent(groupId, k -> new SolverResult.PlacedPair[slots]);
    }
}
