package com.karyakina.schedule.service.generator;

import com.karyakina.schedule.domain.Classroom;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Сетка недели: рабочие дни, расписание звонков и фонд аудиторий.
 * Значения совпадают с теми, что использовались в прежней версии генератора,
 * поэтому уже сохранённое расписание корректно ложится в новую сетку.
 *
 * <p>Аудитории теперь читаются из БД ({@link #rooms(List)}, см. {@code ClassroomRepository}).
 * Список {@link #CLASSROOMS} остаётся как резервный набор на случай, если администратор
 * ещё не завёл ни одной аудитории (пустая таблица) — так демо/новый проект продолжает
 * работать «из коробки», а после первого импорта аудиторий (лист «Аудитории» в файле
 * импорта) или ручного добавления автоматически переключается на данные из БД.
 */
public final class GenerationGrid {

    public static final DayOfWeek[] WORK_DAYS = {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
    };

    public static final String[] DAY_NAMES = {
            "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"
    };

    // Расписание звонков зафиксировано ТЗ — 10 академических часов по 45 мин, которые
    // ПОПАРНО образуют 5 настоящих пар (с коротким перерывом внутри каждой пары между
    // двумя её академическими часами) — так же, как во всей остальной системе "пара"
    // = 2 академических часа (см. SolverConfig.academicHoursPerPair=2). Раньше здесь по
    // ошибке было 10 отдельных "пар" по 45 минут — это было внутренне противоречиво
    // (пара по всей системе считается как 2 часа, а тут выходило line 1 час = 1 "пара"),
    // и физически не соответствовало реальному расписанию звонков техникума: пара 1 —
    // это 08:00-08:45 + 08:50-09:35 вместе (два звонка), а не два отдельных занятия.
    //
    // Если где-то в БД уже есть Schedule/LessonInstance, сохранённые под старую (неверную)
    // 10-слотовую сетку, они не совпадут ни с одним индексом новой 5-слотовой сетки —
    // такое расписание нужно пересобрать автосоставлением заново.
    public static final LocalTime[][] TIME_SLOTS = {
            {LocalTime.of(8, 0), LocalTime.of(9, 35)},   // пара 1 = звонки 1+2 (08:00-08:45, 08:50-09:35)
            {LocalTime.of(9, 40), LocalTime.of(11, 25)}, // пара 2 = звонки 3+4 (09:40-10:25, 10:40-11:25)
            {LocalTime.of(11, 40), LocalTime.of(13, 25)},// пара 3 = звонки 5+6 (11:40-12:25, 12:40-13:25)
            {LocalTime.of(13, 35), LocalTime.of(15, 10)},// пара 4 = звонки 7+8 (13:35-14:20, 14:25-15:10)
            {LocalTime.of(15, 15), LocalTime.of(16, 50)},// пара 5 = звонки 9+10 (15:15-16:00, 16:05-16:50)
    };

    private static final String[][] CLASSROOMS = {
            {"101", "20"}, {"102", "20"}, {"201", "30"}, {"202", "30"},
            {"301", "45"}, {"302", "45"}, {"401", "60"}, {"402", "60"},
            {"Актовый зал", "120"}, {"Лаб. 1", "15"}, {"Лаб. 2", "15"},
    };

    /**
     * Аудитория: название (оно же значение {@code Schedule.classroom}), вместимость
     * и, опционально, набор дисциплин, для которых она закреплена.
     *
     * @param allowedDisciplines названия дисциплин в нижнем регистре, для которых
     *                           разрешено ставить пары в этой аудитории; пустой набор
     *                           означает, что аудитория открыта для ЛЮБОЙ дисциплины
     *                           (см. {@link #isOpenFor(String)})
     */
    public record Room(String name, int capacity, Set<String> allowedDisciplines) {
        public Room {
            allowedDisciplines = allowedDisciplines == null ? Set.of() : Set.copyOf(allowedDisciplines);
        }

        public Room(String name, int capacity) {
            this(name, capacity, Set.of());
        }

        /** Разрешена ли в этой аудитории данная дисциплина (без учёта регистра). */
        public boolean isOpenFor(String disciplineName) {
            if (allowedDisciplines.isEmpty() || disciplineName == null) {
                return true;
            }
            return allowedDisciplines.contains(disciplineName.trim().toLowerCase());
        }
    }

    private GenerationGrid() {
    }

    public static int days() {
        return WORK_DAYS.length;
    }

    public static int pairsPerDay() {
        return TIME_SLOTS.length;
    }

    public static int slotCount() {
        return days() * pairsPerDay();
    }

    public static int flat(int dayIdx, int pairIdx) {
        return dayIdx * pairsPerDay() + pairIdx;
    }

    public static int dayOf(int flat) {
        return flat / pairsPerDay();
    }

    public static int pairOf(int flat) {
        return flat % pairsPerDay();
    }

    public static LocalTime start(int pairIdx) {
        return TIME_SLOTS[pairIdx][0];
    }

    public static LocalTime end(int pairIdx) {
        return TIME_SLOTS[pairIdx][1];
    }

    public static String dayName(int dayIdx) {
        return dayIdx >= 0 && dayIdx < DAY_NAMES.length ? DAY_NAMES[dayIdx] : "День " + (dayIdx + 1);
    }

    public static int dayIndex(DayOfWeek dayOfWeek) {
        for (int i = 0; i < WORK_DAYS.length; i++) {
            if (WORK_DAYS[i] == dayOfWeek) {
                return i;
            }
        }
        return -1;
    }

    /** Индекс пары по времени начала; -1, если время не из сетки звонков. */
    public static int pairIndex(LocalTime startTime) {
        if (startTime == null) {
            return -1;
        }
        for (int i = 0; i < TIME_SLOTS.length; i++) {
            if (TIME_SLOTS[i][0].equals(startTime)) {
                return i;
            }
        }
        return -1;
    }

    /** Резервный фонд аудиторий — используется, только если в БД ещё ничего не заведено. */
    public static List<Room> rooms() {
        List<Room> result = new ArrayList<>(CLASSROOMS.length);
        for (String[] room : CLASSROOMS) {
            int capacity;
            try {
                capacity = Integer.parseInt(room[1]);
            } catch (NumberFormatException e) {
                capacity = 0;
            }
            result.add(new Room(room[0], capacity));
        }
        return result;
    }

    /**
     * Фонд аудиторий из БД. Если таблица пуста (администратор ещё не добавил ни одной
     * аудитории вручную и не импортировал лист «Аудитории»), используется резервный
     * список {@link #rooms()}, чтобы генерация расписания работала «из коробки».
     */
    public static List<Room> rooms(List<Classroom> dbClassrooms) {
        if (dbClassrooms == null || dbClassrooms.isEmpty()) {
            return rooms();
        }
        return dbClassrooms.stream()
                .map(c -> new Room(
                        c.getName(),
                        c.getCapacity() == null ? 0 : c.getCapacity(),
                        c.getAllowedDisciplines() == null ? Set.of()
                                : c.getAllowedDisciplines().stream()
                                        .filter(d -> d != null && !d.isBlank())
                                        .map(d -> d.trim().toLowerCase())
                                        .collect(Collectors.toUnmodifiableSet())))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** Пересекается ли пара с обеденным окном группы. */
    public static boolean overlapsLunch(int pairIdx, LocalTime lunchStart, LocalTime lunchEnd) {
        if (lunchStart == null || lunchEnd == null) {
            return false;
        }
        return start(pairIdx).isBefore(lunchEnd) && lunchStart.isBefore(end(pairIdx));
    }
}

