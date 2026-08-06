package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.StudyGroup;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.dto.ScheduleGenerationResultDto;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.repository.TeacherRepository;
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
    private final TeacherRepository teacherRepository;
    private final TeacherAssignmentService teacherAssignmentService;
    private final ScheduleChangeNotifier scheduleChangeNotifier;
    private final MonthlyRecordService monthlyRecordService;
    private final SettingsService settingsService;

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
    public ScheduleGenerationResultDto generate(Integer academicYear, boolean persist, String adminName) {
        List<TeacherLoad> allLoads = loadRepository.findByAcademicYear(academicYear);
        List<Teacher> allTeachers = teacherRepository.findAll();
        List<Schedule> existingSchedules = scheduleRepository.findByAcademicYear(academicYear);

        // Подбор преподавателя для записей без явно указанного (teacher == null) —
        // ЧИСТОЕ вычисление, ничего не сохраняет. Один преподаватель, ведущий несколько
        // дисциплин, учитывается через Teacher.specialization; одна и та же группа
        // при этом абсолютно нормально получает разные пары от разных преподавателей —
        // это уже было так на уровне модели (TeacherLoad = преподаватель+группа+
        // дисциплина, а не преподаватель+группа), просто раньше без него нельзя было
        // импортировать строку без явного ФИО преподавателя.
        TeacherAssignmentService.Resolution assignment = teacherAssignmentService.resolve(allLoads, allTeachers);

        Set<Long> loadsWithSchedule = new HashSet<>();
        existingSchedules.forEach(s -> loadsWithSchedule.add(s.getTeacherLoad().getId()));

        List<TeacherLoad> toSchedule = allLoads.stream()
                .filter(l -> !loadsWithSchedule.contains(l.getId()))
                .sorted((a, b) -> Integer.compare(sessionsPerWeek(b), sessionsPerWeek(a)))
                .toList();

        State state = new State();
        for (Schedule s : existingSchedules) {
            // Существующая пара уже имеет реально назначенного преподавателя
            // (записей без преподавателя в расписании быть не может).
            state.markBusy(s, s.getTeacherLoad().getTeacher().getId());
        }

        List<Schedule> created = new ArrayList<>();
        List<String> conflicts = new ArrayList<>(assignment.conflicts);
        int unresolvedLoads = 0;

        // ПРЕДВАРИТЕЛЬНАЯ ПРОВЕРКА ВМЕСТИМОСТИ — до попытки размещения. Раньше о
        // нехватке слотов узнавали только постфактум, по десяткам одинаковых "не
        // удалось разместить пару N/18" на каждую нагрузку. Здесь заранее прикидываем
        // (по данным ДО этого прогона — без учёта других нагрузок, которые будут
        // размещаться в этом же прогоне, поэтому оценка оптимистичная), хватит ли
        // вообще физически свободных слотов у группы и у преподавателя, и если нет —
        // выводим одно ясное предупреждение НАВЕРХУ отчёта вместо стены конфликтов.
        List<String> capacityWarnings = new ArrayList<>();

        // ДУБЛИКАТЫ НАГРУЗКИ: если одна и та же тройка преподаватель+дисциплина+группа
        // встречается НЕСКОЛЬКИМИ записями (например, с разными часами — 1200ч и
        // 17784ч), они конкурируют за одни и те же слоты и почти гарантированно дают
        // "не удалось разместить". Это не лечится автосоставлением — записи нужно
        // вручную свести/удалить лишние в таблице нагрузок.
        Map<String, List<TeacherLoad>> byTriple = new HashMap<>();
        for (TeacherLoad load : allLoads) {
            String key = (load.getTeacher() != null ? load.getTeacher().getId() : "auto") + "|"
                    + load.getDiscipline().getId() + "|" + load.getGroup().getId();
            byTriple.computeIfAbsent(key, k -> new ArrayList<>()).add(load);
        }
        for (List<TeacherLoad> group : byTriple.values()) {
            if (group.size() <= 1) continue;
            TeacherLoad first = group.get(0);
            String hoursList = group.stream()
                    .map(l -> String.valueOf(l.getPlannedHours() != null ? l.getPlannedHours() : 0))
                    .reduce((a, b) -> a + ", " + b).orElse("");
            capacityWarnings.add(String.format(
                    "Дубликат нагрузки: %s / %s / группа %s — найдено %d одинаковых записей с планом " +
                    "часов (%s) — они будут конкурировать за одни и те же слоты. Удалите лишние вручную " +
                    "в таблице нагрузок, оставив одну верную запись",
                    first.getTeacher() != null ? first.getTeacher().getFullName() : "преподаватель не назначен",
                    first.getDiscipline().getName(), first.getGroup().getName(), group.size(), hoursList));
        }

        for (TeacherLoad load : toSchedule) {
            Teacher teacher = effectiveTeacher(load, assignment);
            if (teacher == null) continue;
            int needed = sessionsPerWeek(load);
            int groupFree = countFreeGroupSlots(load.getGroup(), state);
            int teacherFree = countFreeTeacherSlots(teacher, state);
            int feasible = Math.min(groupFree, teacherFree);
            if (needed > feasible) {
                capacityWarnings.add(String.format(
                        "%s / %s / группа %s: по плану нужно %d пар/нед, а ориентировочно физически " +
                        "доступно максимум %d (свободно у группы: %d слотов с учётом обеда, у " +
                        "преподавателя: %d слотов с учётом остальной нагрузки). Проверьте часы в файле " +
                        "импорта (пункт про 36 ч/нед на дисциплину) либо перераспределите нагрузку между " +
                        "преподавателями",
                        teacher.getFullName(), load.getDiscipline().getName(), load.getGroup().getName(),
                        needed, feasible, groupFree, teacherFree));
            }
        }

        for (TeacherLoad load : toSchedule) {
            Teacher teacher = effectiveTeacher(load, assignment);
            if (teacher == null) {
                // Не удалось подобрать преподавателя вообще — сообщение уже добавлено
                // в assignment.conflicts, здесь просто учитываем как нерешённую нагрузку.
                unresolvedLoads++;
                continue;
            }

            int needed = sessionsPerWeek(load);
            int placedForLoad = 0;

            int failedForLoad = 0;
            for (int session = 0; session < needed; session++) {
                Placement best = findBestPlacement(load, teacher, state);
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
                    state.markBusy(schedule, teacher.getId());
                    created.add(schedule);
                    placedForLoad++;
                } else {
                    failedForLoad++;
                }
            }
            // ОДНО сообщение на нагрузку вместо строки на каждую непоставленную пару —
            // раньше при нехватке слотов на 18 пар в неделю список конфликтов заполнялся
            // 18 практически одинаковыми строками ("пара 1/18", "пара 2/18", ...), из-за
            // чего отчёт был нечитаемым (сотни строк). Сама причина уже видна из
            // "Предварительной проверки вместимости" выше (см. capacityWarnings).
            if (failedForLoad > 0) {
                conflicts.add(String.format(
                        "Не удалось разместить %d из %d пар: %s, %s, группа %s — нет свободных слотов " +
                        "без нарушения ограничений (обед, занятость преподавателя/группы, максимум пар в день)",
                        failedForLoad, needed, teacher.getFullName(),
                        load.getDiscipline().getName(), load.getGroup().getName()));
            }

            if (placedForLoad < needed) {
                unresolvedLoads++;
            }

            long approxWeeklyHours = needed * 2L;
            if (!Boolean.TRUE.equals(load.getOverload())
                    && approxWeeklyHours * APPROX_WEEKS_PER_YEAR > load.getPlannedHours() * 1.1) {
                conflicts.add(String.format(
                        "Превышение плановой нагрузки: %s / %s / %s — расчётно ~%d ч/год при плане %d ч",
                        teacher.getFullName(), load.getDiscipline().getName(), load.getGroup().getName(),
                        approxWeeklyHours * APPROX_WEEKS_PER_YEAR, load.getPlannedHours()));
            }
        }

        for (TeacherLoad load : toSchedule) {
            Teacher teacher = effectiveTeacher(load, assignment);
            if (teacher == null) continue;
            long placedForThisLoad = created.stream().filter(s -> s.getTeacherLoad().getId().equals(load.getId())).count();
            int scheduledWeeklyHours = (int) (placedForThisLoad * 2);
            int scheduledYearlyHours = scheduledWeeklyHours * APPROX_WEEKS_PER_YEAR;
            int planned = load.getPlannedHours() != null ? load.getPlannedHours() : 0;
            if (planned > 0) {
                double deviation = Math.abs(scheduledYearlyHours - planned) / (double) planned;
                if (deviation > 0.05) {
                    conflicts.add(String.format(
                            "Отклонение от плана > 5%%: %s / %s / %s — расписано ~%d ч/год при плане %d ч",
                            teacher.getFullName(), load.getDiscipline().getName(),
                            load.getGroup().getName(), scheduledYearlyHours, planned));
                }
            }
        }

        if (persist) {
            // Фиксируем автоподбор преподавателя ТОЛЬКО при реальном сохранении —
            // просмотр черновика (persist=false) не должен менять никакие данные,
            // иначе повторный просмотр черновика был бы не идемпотентным.
            for (TeacherLoad load : toSchedule) {
                if (load.getTeacher() == null) {
                    Teacher resolved = assignment.resolvedTeacherByLoadId.get(load.getId());
                    if (resolved != null) {
                        load.setTeacher(resolved);
                        loadRepository.save(load);
                    }
                }
            }
            if (!created.isEmpty()) {
                scheduleRepository.saveAll(created);
                // Помесячный учёт (MonthlyRecord.hours) раньше оставался нулевой заглушкой —
                // теперь пересчитываем его по факту сгенерированного расписания для каждой
                // затронутой нагрузки (учитываются и уже существовавшие пары этой нагрузки).
                Map<Long, List<Schedule>> schedulesByLoadId = new HashMap<>();
                for (Schedule s : existingSchedules) {
                    schedulesByLoadId.computeIfAbsent(s.getTeacherLoad().getId(), k -> new ArrayList<>()).add(s);
                }
                for (Schedule s : created) {
                    schedulesByLoadId.computeIfAbsent(s.getTeacherLoad().getId(), k -> new ArrayList<>()).add(s);
                }
                Set<Long> touchedLoadIds = new HashSet<>();
                for (Schedule s : created) touchedLoadIds.add(s.getTeacherLoad().getId());
                for (Long loadId : touchedLoadIds) {
                    try {
                        TeacherLoad load = loadRepository.findById(loadId).orElse(null);
                        if (load != null) {
                            monthlyRecordService.recalculateHoursForLoad(load, schedulesByLoadId.getOrDefault(loadId, List.of()));
                        }
                    } catch (Exception syncEx) {
                        log.warn("Не удалось пересчитать помесячный учёт для нагрузки {}: {}", loadId, syncEx.getMessage());
                    }
                }
                // Оповещаем преподавателей о том, что для них появились новые пары —
                // раньше это происходило только при ручном добавлении пары, а после
                // автосоставления уведомление никогда не отправлялось.
                if (adminName != null) {
                    for (Schedule schedule : created) {
                        try {
                            scheduleChangeNotifier.pairCreated(schedule, adminName);
                        } catch (Exception notifyEx) {
                            log.warn("Не удалось отправить уведомление о новой паре (teacherLoadId={}): {}",
                                    schedule.getTeacherLoad().getId(), notifyEx.getMessage());
                        }
                    }
                }
            }
        }

        return ScheduleGenerationResultDto.builder()
                .totalLoadsConsidered(toSchedule.size())
                .placedLessons(created.size())
                .unresolvedLoads(unresolvedLoads)
                .createdSchedules(created)
                .capacityWarnings(capacityWarnings)
                .conflicts(conflicts)
                .build();
    }

    /** Реальный преподаватель записи: явно указанный, либо подобранный автоподбором. */
    private Teacher effectiveTeacher(TeacherLoad load, TeacherAssignmentService.Resolution assignment) {
        return load.getTeacher() != null ? load.getTeacher() : assignment.resolvedTeacherByLoadId.get(load.getId());
    }

    private static class Placement {
        int dayIdx;
        int slotIdx;
        String classroom;
        int score;
    }

    /**
     * Обед. Администратор задаёт обеденный перерыв либо для конкретной группы
     * (StudyGroup.lunchStart/lunchEnd — приоритетно), либо для всего потока целиком
     * (SettingsService.getGlobalLunchWindow — если для группы своё не задано). Слот,
     * пересекающийся с этим интервалом, при составлении расписания не используется.
     */
    private boolean slotOverlapsLunch(StudyGroup group, int slotIdx) {
        LocalTime[] window = effectiveLunchWindow(group);
        if (window == null) return false;
        LocalTime slotStart = TIME_SLOTS[slotIdx][0];
        LocalTime slotEnd = TIME_SLOTS[slotIdx][1];
        return slotStart.isBefore(window[1]) && window[0].isBefore(slotEnd);
    }

    private LocalTime[] effectiveLunchWindow(StudyGroup group) {
        if (group.getLunchStart() != null && group.getLunchEnd() != null) {
            return new LocalTime[]{group.getLunchStart(), group.getLunchEnd()};
        }
        return settingsService.getGlobalLunchWindow();
    }

    /** Свободных слотов у группы за неделю (без учёта обеда и уже занятых слотов). */
    private int countFreeGroupSlots(StudyGroup group, State state) {
        int free = 0;
        for (int dayIdx = 0; dayIdx < WORK_DAYS.length; dayIdx++) {
            for (int slotIdx = 0; slotIdx < TIME_SLOTS.length; slotIdx++) {
                if (slotOverlapsLunch(group, slotIdx)) continue;
                if (state.isGroupBusy(group.getId(), dayIdx, slotIdx)) continue;
                free++;
            }
        }
        return free;
    }

    /** Свободных слотов у преподавателя за неделю (с учётом максимума пар в день). */
    private int countFreeTeacherSlots(Teacher teacher, State state) {
        int maxPerDay = teacher.getMaxPairsPerDay() != null ? teacher.getMaxPairsPerDay() : DEFAULT_MAX_PAIRS_PER_DAY;
        int free = 0;
        for (int dayIdx = 0; dayIdx < WORK_DAYS.length; dayIdx++) {
            int dailyFree = 0;
            for (int slotIdx = 0; slotIdx < TIME_SLOTS.length && dailyFree < maxPerDay; slotIdx++) {
                if (state.isTeacherBusy(teacher.getId(), dayIdx, slotIdx)) continue;
                dailyFree++;
            }
            free += dailyFree;
        }
        return free;
    }

    private Placement findBestPlacement(TeacherLoad load, Teacher teacher, State state) {
        Long teacherId = teacher.getId();
        Long groupId = load.getGroup().getId();
        int maxPerDay = teacher.getMaxPairsPerDay() != null
                ? teacher.getMaxPairsPerDay() : DEFAULT_MAX_PAIRS_PER_DAY;

        List<Integer> preferredDays = preferredDayIndexes(load);
        List<Integer> preferredSlots = preferredSlotIndexes(load);

        Placement best = null;

        for (int dayIdx = 0; dayIdx < WORK_DAYS.length; dayIdx++) {
            int dailyCount = state.dailyCount(teacherId, dayIdx);
            if (dailyCount >= maxPerDay) continue;

            for (int slotIdx = 0; slotIdx < TIME_SLOTS.length; slotIdx++) {
                if (state.isTeacherBusy(teacherId, dayIdx, slotIdx)) continue;
                if (state.isGroupBusy(groupId, dayIdx, slotIdx)) continue;
                if (slotOverlapsLunch(load.getGroup(), slotIdx)) continue;

                String classroom = pickClassroom(load.getGroup(), state, dayIdx, slotIdx);
                if (classroom == null) continue;

                int score = scorePlacement(load, teacher, state, dayIdx, slotIdx, classroom, preferredDays, preferredSlots);

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

    private int scorePlacement(TeacherLoad load, Teacher teacher, State state, int dayIdx, int slotIdx, String classroom,
                                List<Integer> preferredDays, List<Integer> preferredSlots) {
        int score = 0;
        Long teacherId = teacher.getId();

        if (TeacherAssignmentService.matchesSpecialization(teacher, load.getDiscipline().getName())) {
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

        // ЗАЩИТА ОТ ПЕРЕУТОМЛЕНИЯ: у одной группы не должно стоять 2 одинаковые пары
        // подряд с одним и тем же преподавателем (например, 4 часа подряд одного и того
        // же "Программирования"). Это не жёсткий запрет (при явной нехватке слотов лучше
        // всё-таки расставить нагрузку, чем оставить её вовсе непоставленной), а очень
        // сильный штраф — такой вариант выбирается только если все остальные варианты
        // для этой пары исчерпаны/хуже.
        if (state.groupSlotHasSameAdjacent(load.getGroup().getId(), dayIdx, slotIdx,
                load.getDiscipline().getId(), teacherId)) {
            score -= 1000;
        }

        return score;
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

    /** Максимум часов в неделю на одну дисциплину у одного преподавателя (18 пар = 36ч). */
    private static final int MAX_HOURS_PER_WEEK_PER_LOAD = 36;

    private int sessionsPerWeek(TeacherLoad load) {
        double hoursPerWeek;
        if (load.getHoursPerWeek() != null && load.getHoursPerWeek() > 0) {
            hoursPerWeek = load.getHoursPerWeek();
        } else {
            int planned = load.getPlannedHours() != null ? load.getPlannedHours() : 0;
            hoursPerWeek = planned / (double) APPROX_WEEKS_PER_YEAR;
        }
        // Защита от нереалистичных данных (например, если в "часов за год" по ошибке
        // попало суммарное/годовое число, а не часы одной дисциплины на одну группу) —
        // такие значения не должны заставлять генератор пытаться впихнуть 18+ пар
        // в неделю, где физически нет столько слотов. См. также проверку при импорте
        // (ImportService), которая должна отсеивать такие строки ещё раньше.
        hoursPerWeek = Math.min(hoursPerWeek, MAX_HOURS_PER_WEEK_PER_LOAD);
        return Math.max(1, (int) Math.round(hoursPerWeek / 2.0));
    }

    /**
     * Разбирает ячейку "Предпочтительные дни и время" из импорта. В файле это
     * свободный текст на русском ("Пн, Ср", "понедельник, среда, до 12:00" и т.п.),
     * а не английские имена DayOfWeek — раньше здесь стоял DayOfWeek.valueOf(...),
     * который никогда не совпадал с реальными данными импорта, поэтому предпочтения
     * по дням фактически всегда игнорировались.
     */
    private List<Integer> preferredDayIndexes(TeacherLoad load) {
        if (load.getPreferredDays() == null || load.getPreferredDays().isBlank()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (String token : load.getPreferredDays().split("[,;]")) {
            DayOfWeek dow = parseRussianOrEnglishDay(token);
            if (dow == null) continue;
            for (int i = 0; i < WORK_DAYS.length; i++) {
                if (WORK_DAYS[i] == dow && !result.contains(i)) result.add(i);
            }
        }
        return result;
    }

    /** Достаёт день недели из свободного текста токена ("Пн", "среда", "MONDAY", "Ср до 12:00"). */
    private DayOfWeek parseRussianOrEnglishDay(String token) {
        if (token == null) return null;
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return null;

        // Берём только начальную буквенную часть токена — дальше может идти время
        // ("Ср 9:00-12:00", "среда до обеда").
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^[А-Яа-яЁёA-Za-z]+")
                .matcher(trimmed);
        if (!m.find()) return null;
        String word = m.group().toUpperCase(Locale.ROOT).replace("Ё", "Е");

        return switch (word) {
            case "ПН", "ПОНЕДЕЛЬНИК" -> DayOfWeek.MONDAY;
            case "ВТ", "ВТОРНИК" -> DayOfWeek.TUESDAY;
            case "СР", "СРЕДА" -> DayOfWeek.WEDNESDAY;
            case "ЧТ", "ЧЕТВЕРГ" -> DayOfWeek.THURSDAY;
            case "ПТ", "ПЯТНИЦА" -> DayOfWeek.FRIDAY;
            case "СБ", "СУББОТА" -> DayOfWeek.SATURDAY;
            case "ВС", "ВОСКРЕСЕНЬЕ" -> DayOfWeek.SUNDAY;
            default -> {
                try {
                    yield DayOfWeek.valueOf(word); // на случай английских названий в файле
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
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

        /**
         * ЗАЩИТА ОТ ПЕРЕУТОМЛЕНИЯ: для каждой группы храним, какая пара (дисциплина+
         * преподаватель) стоит в каждом слоте недели. Используется в scorePlacement,
         * чтобы штрафовать (не запрещать намертво — иначе при нехватке слотов нагрузка
         * рискует остаться нерасставленной) постановку одной и той же пары ДВА раза
         * подряд у одной группы — см. groupSlotHasSame(...).
         */
        final Map<Long, Object[][]> groupSlotContent = new HashMap<>();

        void markBusy(Schedule s, Long teacherId) {
            int dayIdx = dayIndex(s.getDayOfWeek());
            int slotIdx = slotIndex(s.getStartTime());
            Long groupId = s.getTeacherLoad().getGroup().getId();

            if (dayIdx < 0) return;

            boolean[][] tBusy = teacherBusy.computeIfAbsent(teacherId, k -> new boolean[WORK_DAYS.length][TIME_SLOTS.length]);
            boolean[][] gBusy = groupBusy.computeIfAbsent(groupId, k -> new boolean[WORK_DAYS.length][TIME_SLOTS.length]);
            int[] daily = teacherDailyCount.computeIfAbsent(teacherId, k -> new int[WORK_DAYS.length]);
            Object[][] gContent = groupSlotContent.computeIfAbsent(groupId, k -> new Object[WORK_DAYS.length][TIME_SLOTS.length]);

            if (slotIdx >= 0) {
                tBusy[dayIdx][slotIdx] = true;
                gBusy[dayIdx][slotIdx] = true;
                if (s.getClassroom() != null) {
                    boolean[][] rBusy = roomBusy.computeIfAbsent(s.getClassroom(), k -> new boolean[WORK_DAYS.length][TIME_SLOTS.length]);
                    rBusy[dayIdx][slotIdx] = true;
                }
                Long disciplineId = s.getTeacherLoad().getDiscipline() != null ? s.getTeacherLoad().getDiscipline().getId() : null;
                gContent[dayIdx][slotIdx] = new Object[]{disciplineId, teacherId};
            } else {
                Arrays.fill(tBusy[dayIdx], true);
                Arrays.fill(gBusy[dayIdx], true);
            }
            daily[dayIdx]++;
        }

        /**
         * Правда ли, что в соседнем (предыдущем или следующем) слоте ЭТОГО ЖЕ дня у ЭТОЙ
         * ЖЕ группы уже стоит та же пара (дисциплина+преподаватель) — т.е. постановка
         * кандидата сюда даст 2 одинаковые пары подряд.
         */
        boolean groupSlotHasSameAdjacent(Long groupId, int dayIdx, int slotIdx, Long disciplineId, Long teacherId) {
            Object[][] content = groupSlotContent.get(groupId);
            if (content == null || disciplineId == null || teacherId == null) return false;
            return matches(content, dayIdx, slotIdx - 1, disciplineId, teacherId)
                    || matches(content, dayIdx, slotIdx + 1, disciplineId, teacherId);
        }

        private boolean matches(Object[][] content, int dayIdx, int slotIdx, Long disciplineId, Long teacherId) {
            if (slotIdx < 0 || slotIdx >= TIME_SLOTS.length) return false;
            Object cell = content[dayIdx][slotIdx];
            if (!(cell instanceof Object[] pair)) return false;
            return disciplineId.equals(pair[0]) && teacherId.equals(pair[1]);
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
