package com.karyakina.schedule.service.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * ЯДРО АВТОСОСТАВЛЕНИЯ.
 *
 * <p>Прежняя версия ставила пары «первым подходящим по баллу» слотом и, если место
 * кончалось, просто записывала «не удалось разместить». Здесь четыре отличия:
 * <ol>
 *   <li><b>Раунды.</b> Нагрузки разворачиваются в отдельные пары и раскладываются по раундам:
 *       сначала по одной паре каждой нагрузки, потом вторые и т.д. Дисциплины расползаются
 *       по неделе сами, а не слипаются в первые два дня.</li>
 *   <li><b>Оценка кандидатов.</b> Для каждой пары перебираются все допустимые «слот + аудитория»,
 *       выбирается минимальный штраф мягких ограничений (см. {@link SoftScorer}) со случайным
 *       выбором среди нескольких лучших — это и даёт разнообразие между рестартами.</li>
 *   <li><b>Выталкивание (ejection).</b> Если места нет вообще, уже стоящая пара переносится,
 *       освобождая слот. Именно это чаще всего спасает «последние» пары плотной нагрузки.</li>
 *   <li><b>Локальное улучшение + рестарты.</b> Случайные переносы, уменьшающие штраф
 *       (окна, повторы рисунка недели, неравномерность), затем весь прогон повторяется
 *       с другим seed'ом; побеждает решение с наименьшим числом нерасставленных пар.</li>
 * </ol>
 *
 * <p>Исключений не бросает никогда: всё, что не встало, возвращается в
 * {@link SolverResult#unplaced()} с диагнозом, и вызывающий сервис превращает это
 * в вопрос администратору.
 */
@Component
public class ScheduleSolver {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSolver.class);

    /** Из скольких лучших вариантов выбирается слот (рандомизация для рестартов). */
    private static final int TOP_CANDIDATES = 3;

    @Value("${schedule.solver.max-millis:20000}")
    private long maxMillis = 20_000L;

    public SolverResult solve(SolverInput input) {
        return solve(input, index -> {
        });
    }

    /**
     * @param preOccupy сюда попадает свежий индекс до расстановки — чтобы отметить занятыми
     *                  слоты уже существующих в БД пар
     */
    public SolverResult solve(SolverInput input, Consumer<OccupancyIndex> preOccupy) {
        long deadline = System.currentTimeMillis() + Math.max(1000L, maxMillis);
        SolverResult best = null;
        for (int restart = 0; restart < input.config().restarts(); restart++) {
            SolverResult candidate = runOnce(input, preOccupy,
                    new Random(input.config().randomSeed() + restart * 7919L), deadline);
            if (candidate.betterThan(best)) {
                best = candidate;
            }
            if (System.currentTimeMillis() > deadline) {
                log.debug("Автосоставление остановлено по таймауту после {} рестартов", restart + 1);
                break;
            }
        }
        return best == null ? SolverResult.empty() : best;
    }

    // ------------------------------------------------------------------ один прогон

    private SolverResult runOnce(SolverInput input, Consumer<OccupancyIndex> preOccupy,
                                 Random random, long deadline) {
        SolverConfig config = input.config();
        SoftScorer scorer = new SoftScorer(config);
        OccupancyIndex index = new OccupancyIndex(config);
        preOccupy.accept(index);

        Map<Long, SolverInput.GroupRef> groups = input.groupsById();
        Map<Long, SolverInput.TeacherRef> teachers = input.teachersById();
        Map<Long, List<GenerationGrid.Room>> roomsByDemand = suitableRooms(input);
        Map<Long, Double> targetPerDay = targetPairsPerDay(input);

        List<SolverResult.PlacedPair> placed = new ArrayList<>();
        Map<Long, Integer> missing = new HashMap<>();
        Map<Long, Map<SolverResult.Reason, Integer>> blockStats = new HashMap<>();

        for (SolverInput.Demand demand : orderUnits(input, random)) {
            SolverInput.GroupRef group = groups.get(demand.groupId());
            SolverInput.TeacherRef teacher = teachers.get(demand.teacherId());
            if (group == null || teacher == null) {
                registerMissing(missing, blockStats, demand.loadId(), SolverResult.Reason.UNKNOWN);
                continue;
            }
            List<GenerationGrid.Room> rooms = roomsByDemand.getOrDefault(demand.loadId(), List.of());
            EnumMap<OccupancyIndex.Violation, Integer> stats = new EnumMap<>(OccupancyIndex.Violation.class);
            double target = targetPerDay.getOrDefault(group.id(), 1.0);

            Placement placement = bestPlacement(index, scorer, demand, group, teacher, rooms, target, random, stats);
            if (placement == null && System.currentTimeMillis() < deadline) {
                // Выталкивание — самая дорогая часть прохода, поэтому за пределами бюджета
                // времени его не запускаем: лучше вернуть честный частичный результат,
                // чем висеть на запросе.
                placement = tryEject(index, scorer, input, demand, group, teacher, rooms,
                        targetPerDay, placed, roomsByDemand);
            }
            if (placement == null) {
                registerMissing(missing, blockStats, demand.loadId(), dominantReason(stats, rooms.isEmpty()));
                continue;
            }
            SolverResult.PlacedPair pair = new SolverResult.PlacedPair(demand.loadId(), group.id(),
                    demand.disciplineId(), teacher.id(), placement.room().name(),
                    placement.dayIdx(), placement.pairIdx());
            index.place(pair);
            placed.add(pair);
        }

        localSearch(index, scorer, input, placed, roomsByDemand, targetPerDay, random, deadline);

        SoftScorer.Metrics metrics = scorer.evaluate(placed);
        List<SolverResult.Unplaced> unplaced = buildUnplaced(input, missing, blockStats);
        placed.sort(Comparator.comparingInt(SolverResult.PlacedPair::dayIndex)
                .thenComparingInt(SolverResult.PlacedPair::pairIndex)
                .thenComparingLong(SolverResult.PlacedPair::groupId));
        return new SolverResult(placed, unplaced, metrics.penalty(), metrics.values());
    }

    // ------------------------------------------------------------------ выбор места

    private record Placement(int dayIdx, int pairIdx, GenerationGrid.Room room, double penalty) {
    }

    private Placement bestPlacement(OccupancyIndex index,
                                    SoftScorer scorer,
                                    SolverInput.Demand demand,
                                    SolverInput.GroupRef group,
                                    SolverInput.TeacherRef teacher,
                                    List<GenerationGrid.Room> rooms,
                                    double targetPerDay,
                                    Random random,
                                    Map<OccupancyIndex.Violation, Integer> stats) {
        return bestPlacement(index, scorer, demand, group, teacher, rooms, targetPerDay, random, stats, -1, -1);
    }

    /** {@code forbiddenDay}/{@code forbiddenPair} = -1, если запретов нет. */
    private Placement bestPlacement(OccupancyIndex index,
                                    SoftScorer scorer,
                                    SolverInput.Demand demand,
                                    SolverInput.GroupRef group,
                                    SolverInput.TeacherRef teacher,
                                    List<GenerationGrid.Room> rooms,
                                    double targetPerDay,
                                    Random random,
                                    Map<OccupancyIndex.Violation, Integer> stats,
                                    int forbiddenDay,
                                    int forbiddenPair) {
        List<Placement> candidates = new ArrayList<>();
        for (int day = 0; day < GenerationGrid.days(); day++) {
            for (int pair = 0; pair < GenerationGrid.pairsPerDay(); pair++) {
                if (day == forbiddenDay && pair == forbiddenPair) {
                    continue;
                }
                for (GenerationGrid.Room room : rooms) {
                    OccupancyIndex.Violation violation = index.check(demand, group, teacher, room, day, pair);
                    if (violation == OccupancyIndex.Violation.NONE) {
                        double penalty = scorer.placementPenalty(index, demand, group, teacher, room,
                                day, pair, targetPerDay);
                        candidates.add(new Placement(day, pair, room, penalty));
                        break; // аудитории отсортированы: берём минимальную достаточную
                    }
                    if (stats != null) {
                        stats.merge(violation, 1, Integer::sum);
                    }
                    // Если слот закрыт не аудиторией — другие аудитории не помогут.
                    if (violation != OccupancyIndex.Violation.ROOM_BUSY
                            && violation != OccupancyIndex.Violation.ROOM_CAPACITY) {
                        break;
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingDouble(Placement::penalty));
        int pool = Math.min(TOP_CANDIDATES, candidates.size());
        return candidates.get(random == null ? 0 : random.nextInt(pool));
    }

    /**
     * Выталкивание глубины 1: освобождаем слот, перенося одну–две мешающие пары.
     * Если пристроить их не удалось — полный откат, состояние не портится.
     */
    private Placement tryEject(OccupancyIndex index,
                               SoftScorer scorer,
                               SolverInput input,
                               SolverInput.Demand demand,
                               SolverInput.GroupRef group,
                               SolverInput.TeacherRef teacher,
                               List<GenerationGrid.Room> rooms,
                               Map<Long, Double> targetPerDay,
                               List<SolverResult.PlacedPair> placed,
                               Map<Long, List<GenerationGrid.Room>> roomsByDemand) {
        Map<Long, SolverInput.Demand> demandById = new HashMap<>();
        input.demands().forEach(d -> demandById.put(d.loadId(), d));
        Map<Long, SolverInput.GroupRef> groups = input.groupsById();
        Map<Long, SolverInput.TeacherRef> teachers = input.teachersById();

        for (int day = 0; day < GenerationGrid.days(); day++) {
            for (int pair = 0; pair < GenerationGrid.pairsPerDay(); pair++) {
                int flat = GenerationGrid.flat(day, pair);
                for (GenerationGrid.Room room : rooms) {
                    List<SolverResult.PlacedPair> blockers = blockersAt(placed, group.id(), teacher.id(),
                            room.name(), flat);
                    if (blockers.isEmpty() || blockers.size() > 2) {
                        continue;
                    }
                    blockers.forEach(index::remove);
                    if (index.check(demand, group, teacher, room, day, pair) != OccupancyIndex.Violation.NONE) {
                        blockers.forEach(index::place);
                        continue;
                    }
                    List<SolverResult.PlacedPair> relocated = new ArrayList<>();
                    boolean ok = true;
                    for (SolverResult.PlacedPair blocker : blockers) {
                        SolverInput.Demand blockerDemand = demandById.get(blocker.loadId());
                        SolverInput.GroupRef blockerGroup = groups.get(blocker.groupId());
                        SolverInput.TeacherRef blockerTeacher = teachers.get(blocker.teacherId());
                        if (blockerDemand == null || blockerGroup == null || blockerTeacher == null) {
                            ok = false;
                            break;
                        }
                        Placement alternative = bestPlacement(index, scorer, blockerDemand, blockerGroup,
                                blockerTeacher, roomsByDemand.getOrDefault(blockerDemand.loadId(), List.of()),
                                targetPerDay.getOrDefault(blockerGroup.id(), 1.0), null, null, day, pair);
                        if (alternative == null) {
                            ok = false;
                            break;
                        }
                        SolverResult.PlacedPair moved = new SolverResult.PlacedPair(blocker.loadId(),
                                blocker.groupId(), blocker.disciplineId(), blocker.teacherId(),
                                alternative.room().name(), alternative.dayIdx(), alternative.pairIdx());
                        index.place(moved);
                        relocated.add(moved);
                    }
                    // Перенесённая пара могла добрать дневной лимит той же группе или
                    // преподавателю, поэтому целевой слот проверяем ещё раз — уже по факту.
                    if (ok && index.check(demand, group, teacher, room, day, pair)
                            != OccupancyIndex.Violation.NONE) {
                        ok = false;
                    }
                    if (ok) {
                        for (int i = 0; i < blockers.size(); i++) {
                            placed.remove(blockers.get(i));
                            placed.add(relocated.get(i));
                        }
                        return new Placement(day, pair, room, 0);
                    }
                    relocated.forEach(index::remove);
                    blockers.forEach(index::place);
                }
            }
        }
        return null;
    }

    private List<SolverResult.PlacedPair> blockersAt(List<SolverResult.PlacedPair> placed, long groupId,
                                                     long teacherId, String room, int flat) {
        List<SolverResult.PlacedPair> blockers = new ArrayList<>(2);
        for (SolverResult.PlacedPair pair : placed) {
            if (pair.flat() != flat) {
                continue;
            }
            if (pair.groupId() == groupId || pair.teacherId() == teacherId || room.equals(pair.room())) {
                blockers.add(pair);
            }
        }
        return blockers;
    }

    // ------------------------------------------------------------------ локальное улучшение

    private void localSearch(OccupancyIndex index,
                             SoftScorer scorer,
                             SolverInput input,
                             List<SolverResult.PlacedPair> placed,
                             Map<Long, List<GenerationGrid.Room>> roomsByDemand,
                             Map<Long, Double> targetPerDay,
                             Random random,
                             long deadline) {
        if (placed.isEmpty()) {
            return;
        }
        Map<Long, SolverInput.Demand> demandById = new HashMap<>();
        input.demands().forEach(d -> demandById.put(d.loadId(), d));
        Map<Long, SolverInput.GroupRef> groups = input.groupsById();
        Map<Long, SolverInput.TeacherRef> teachers = input.teachersById();
        Map<String, GenerationGrid.Room> roomByName = new HashMap<>();
        input.rooms().forEach(r -> roomByName.put(r.name(), r));

        for (int i = 0; i < input.config().localSearchIterations(); i++) {
            if ((i & 255) == 0 && System.currentTimeMillis() > deadline) {
                return;
            }
            int idx = random.nextInt(placed.size());
            SolverResult.PlacedPair current = placed.get(idx);
            SolverInput.Demand demand = demandById.get(current.loadId());
            SolverInput.GroupRef group = groups.get(current.groupId());
            SolverInput.TeacherRef teacher = teachers.get(current.teacherId());
            if (demand == null || group == null || teacher == null) {
                continue;
            }
            double target = targetPerDay.getOrDefault(group.id(), 1.0);

            index.remove(current);
            double currentPenalty = scorer.placementPenalty(index, demand, group, teacher,
                    roomByName.get(current.room()), current.dayIndex(), current.pairIndex(), target);

            Placement alternative = bestPlacement(index, scorer, demand, group, teacher,
                    roomsByDemand.getOrDefault(demand.loadId(), List.of()), target, random, null);

            if (alternative != null && alternative.penalty() < currentPenalty - 0.001) {
                SolverResult.PlacedPair moved = new SolverResult.PlacedPair(current.loadId(), current.groupId(),
                        current.disciplineId(), current.teacherId(), alternative.room().name(),
                        alternative.dayIdx(), alternative.pairIdx());
                index.place(moved);
                placed.set(idx, moved);
            } else {
                index.place(current);
            }
        }
    }

    // ------------------------------------------------------------------ подготовка

    /** Разворачивает нагрузки в отдельные пары и раскладывает их по раундам. */
    private List<SolverInput.Demand> orderUnits(SolverInput input, Random random) {
        Map<Long, Integer> teacherLoad = new HashMap<>();
        input.demands().forEach(d -> teacherLoad.merge(d.teacherId(), d.pairsPerWeek(), Integer::sum));

        List<SolverInput.Demand> sorted = new ArrayList<>(input.demands());
        sorted.sort(Comparator
                .comparingInt((SolverInput.Demand d) -> -difficulty(d, teacherLoad))
                .thenComparingLong(SolverInput.Demand::loadId));

        int maxPairs = sorted.stream().mapToInt(SolverInput.Demand::pairsPerWeek).max().orElse(0);
        List<SolverInput.Demand> units = new ArrayList<>();
        for (int round = 0; round < maxPairs; round++) {
            List<SolverInput.Demand> roundUnits = new ArrayList<>();
            for (SolverInput.Demand demand : sorted) {
                if (demand.pairsPerWeek() > round) {
                    roundUnits.add(demand);
                }
            }
            for (int i = roundUnits.size() - 1; i > 0; i--) {
                if (random.nextInt(4) == 0) {
                    int j = random.nextInt(i + 1);
                    SolverInput.Demand tmp = roundUnits.get(i);
                    roundUnits.set(i, roundUnits.get(j));
                    roundUnits.set(j, tmp);
                }
            }
            units.addAll(roundUnits);
        }
        return units;
    }

    private int difficulty(SolverInput.Demand demand, Map<Long, Integer> teacherLoad) {
        int score = demand.pairsPerWeek() * 2 + teacherLoad.getOrDefault(demand.teacherId(), 0);
        if (!demand.preferredDays().isEmpty()) {
            score += 6;
        }
        if (!demand.preferredPairs().isEmpty()) {
            score += 4;
        }
        return score;
    }

    /** Подходящие аудитории для нагрузки: от минимальной достаточной к большим. */
    private Map<Long, List<GenerationGrid.Room>> suitableRooms(SolverInput input) {
        Map<Long, SolverInput.GroupRef> groups = input.groupsById();
        Map<Long, List<GenerationGrid.Room>> result = new HashMap<>();
        for (SolverInput.Demand demand : input.demands()) {
            SolverInput.GroupRef group = groups.get(demand.groupId());
            int students = group == null ? 0 : group.studentCount();
            // Аудитория подходит, если хватает вместимости И (если у аудитории есть
            // список закреплённых дисциплин) дисциплина нагрузки в этот список входит.
            // Аудитория без явного списка дисциплин открыта для любых пар — так задаются
            // обычные лекционные/семинарские аудитории, в отличие от специализированных
            // лабораторий, для которых список дисциплин указан явно при импорте.
            List<GenerationGrid.Room> suitable = input.rooms().stream()
                    .filter(room -> room.capacity() <= 0 || students <= 0 || room.capacity() >= students)
                    .filter(room -> room.isOpenFor(demand.disciplineName()))
                    .sorted(Comparator.comparingInt(GenerationGrid.Room::capacity))
                    .toList();
            // Если группа не влезает никуда — не оставляем её без вариантов совсем:
            // отдаём самые большие ПОДХОДЯЩИЕ ПО ДИСЦИПЛИНЕ аудитории, а несоответствие
            // по вместимости уйдёт в предупреждения.
            if (suitable.isEmpty()) {
                suitable = input.rooms().stream()
                        .filter(room -> room.isOpenFor(demand.disciplineName()))
                        .sorted(Comparator.comparingInt(GenerationGrid.Room::capacity).reversed())
                        .limit(3)
                        .toList();
            }
            // Если и дисциплина ни в одной аудитории явно не разрешена (например, все
            // аудитории специализированы под другие предметы) — лучше дать хоть какой-то
            // вариант, чем оставить нагрузку совсем без аудиторий.
            result.put(demand.loadId(), suitable.isEmpty()
                    ? input.rooms().stream()
                            .sorted(Comparator.comparingInt(GenerationGrid.Room::capacity).reversed())
                            .limit(3)
                            .toList()
                    : suitable);
        }
        return result;
    }

    /** Целевое число пар в день у группы — основа равномерного распределения по неделе. */
    private Map<Long, Double> targetPairsPerDay(SolverInput input) {
        Map<Long, Integer> totals = new HashMap<>();
        input.demands().forEach(d -> totals.merge(d.groupId(), d.pairsPerWeek(), Integer::sum));
        Map<Long, Double> target = new HashMap<>();
        totals.forEach((groupId, total) -> target.put(groupId, total / (double) GenerationGrid.days()));
        return target;
    }

    // ------------------------------------------------------------------ диагностика

    private void registerMissing(Map<Long, Integer> missing,
                                 Map<Long, Map<SolverResult.Reason, Integer>> blockStats,
                                 long loadId, SolverResult.Reason reason) {
        missing.merge(loadId, 1, Integer::sum);
        blockStats.computeIfAbsent(loadId, k -> new EnumMap<>(SolverResult.Reason.class))
                .merge(reason, 1, Integer::sum);
    }

    private SolverResult.Reason dominantReason(Map<OccupancyIndex.Violation, Integer> stats, boolean noRooms) {
        if (noRooms) {
            // Различаем два разных случая, которые раньше показывали один и тот же текст:
            // "аудиторий вообще нет ни одной подходящей" (проблема с данными — маленькая
            // вместимость/не тот допуск у аудиторий) — и "аудитории есть, но заняты во все
            // проверенные окна" (это уже не про нехватку аудиторий, а про то, что у
            // преподавателя/группы почти нет общих свободных слотов вообще). Раньше оба
            // случая писали "нет свободной аудитории", что сбивало с толку, если аудиторий
            // на самом деле много.
            return SolverResult.Reason.NO_SUITABLE_ROOM_AT_ALL;
        }
        OccupancyIndex.Violation top = stats.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        if (top == null) {
            return SolverResult.Reason.UNKNOWN;
        }
        return switch (top) {
            case GROUP_BUSY -> SolverResult.Reason.GROUP_BUSY;
            case GROUP_SLOT_BLOCKED -> SolverResult.Reason.LUNCH;
            case GROUP_DAY_LIMIT -> SolverResult.Reason.GROUP_DAY_LIMIT;
            case GROUP_WEEK_LIMIT -> SolverResult.Reason.GROUP_WEEK_LIMIT;
            case TEACHER_BUSY -> SolverResult.Reason.TEACHER_BUSY;
            case TEACHER_DAY_LIMIT -> SolverResult.Reason.TEACHER_DAY_LIMIT;
            case TEACHER_WEEK_LIMIT -> SolverResult.Reason.TEACHER_WEEK_LIMIT;
            case ROOM_BUSY -> SolverResult.Reason.NO_ROOM;
            case ROOM_CAPACITY -> SolverResult.Reason.ROOM_CAPACITY;
            case SUBJECT_ROW_LIMIT, SUBJECT_DAY_LIMIT -> SolverResult.Reason.SUBJECT_LIMIT;
            default -> SolverResult.Reason.UNKNOWN;
        };
    }

    private List<SolverResult.Unplaced> buildUnplaced(SolverInput input,
                                                      Map<Long, Integer> missing,
                                                      Map<Long, Map<SolverResult.Reason, Integer>> blockStats) {
        List<SolverResult.Unplaced> result = new ArrayList<>();
        for (SolverInput.Demand demand : input.demands()) {
            Integer pairs = missing.get(demand.loadId());
            if (pairs == null || pairs == 0) {
                continue;
            }
            Map<SolverResult.Reason, Integer> stats = blockStats.getOrDefault(demand.loadId(), Map.of());
            SolverResult.Reason reason = stats.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(SolverResult.Reason.UNKNOWN);
            result.add(new SolverResult.Unplaced(demand.loadId(), demand.groupId(), demand.disciplineId(),
                    demand.teacherId(), pairs, reason, stats));
        }
        return result;
    }

    public void setMaxMillis(long maxMillis) {
        this.maxMillis = maxMillis;
    }
}
