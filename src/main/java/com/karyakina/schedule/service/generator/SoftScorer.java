package com.karyakina.schedule.service.generator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Штрафы за МЯГКИЕ ограничения — чем меньше значение, тем лучше расписание:
 * <ul>
 *   <li>«окна» у группы и у преподавателя;</li>
 *   <li>две одинаковые пары подряд у группы (защита от переутомления);</li>
 *   <li>неравномерная нагрузка по дням недели;</li>
 *   <li>повтор рисунка недели: тот же предмет на той же паре другого дня и та же
 *       последовательность «предмет → предмет», что уже была на другом дне;</li>
 *   <li>пожелания преподавателя по дням и парам;</li>
 *   <li>поздние пары и лишние переходы группы между аудиториями.</li>
 * </ul>
 */
public final class SoftScorer {

    private final SolverConfig config;
    private final SolverConfig.Weights weights;
    private final int pairsPerDay = GenerationGrid.pairsPerDay();
    private final int days = GenerationGrid.days();

    public SoftScorer(SolverConfig config) {
        this.config = config;
        this.weights = config.weights();
    }

    /** Во сколько «обходится» постановка пары в конкретный слот (считается инкрементально). */
    public double placementPenalty(OccupancyIndex index,
                                   SolverInput.Demand demand,
                                   SolverInput.GroupRef group,
                                   SolverInput.TeacherRef teacher,
                                   GenerationGrid.Room room,
                                   int dayIdx,
                                   int pairIdx,
                                   double targetPairsPerDay) {
        int flat = GenerationGrid.flat(dayIdx, pairIdx);
        double penalty = 0;

        penalty += weights.groupGap() * gapDelta(index.groupTimeline(group.id()), dayIdx, flat);

        if (teacher != null) {
            penalty += weights.teacherGap() * gapDelta(index.teacherTimeline(teacher.id()), dayIdx, flat);
            if (!teacher.preferredDays().isEmpty() && !teacher.preferredDays().contains(dayIdx)) {
                penalty += weights.preferredDayMiss();
            }
        }
        if (!demand.preferredDays().isEmpty() && !demand.preferredDays().contains(dayIdx)) {
            penalty += weights.preferredDayMiss();
        }
        if (!demand.preferredPairs().isEmpty() && !demand.preferredPairs().contains(pairIdx)) {
            penalty += weights.preferredDayMiss() / 2;
        }

        // ЗАЩИТА ОТ ПЕРЕУТОМЛЕНИЯ: соседняя пара той же дисциплины, тем более того же преподавателя.
        long[] disciplines = index.groupDisciplines(group.id());
        long[] teachers = index.groupTeachers(group.id());
        for (int neighbour : new int[]{pairIdx - 1, pairIdx + 1}) {
            if (neighbour < 0 || neighbour >= pairsPerDay) {
                continue;
            }
            int neighbourFlat = GenerationGrid.flat(dayIdx, neighbour);
            if (disciplines[neighbourFlat] == demand.disciplineId()) {
                penalty += weights.sameSubjectAdjacent();
                if (teacher != null && teachers[neighbourFlat] == teacher.id()) {
                    penalty += weights.sameSubjectAdjacent() * 0.5;
                }
            }
        }

        // Равномерность по дням: квадратичное отклонение от целевого числа пар в день.
        int after = index.groupDayCount(group.id(), dayIdx) + 1;
        penalty += weights.dayImbalance()
                * (sq(after - targetPairsPerDay) - sq(after - 1 - targetPairsPerDay));

        penalty += weights.patternRepeat() * patternRepeats(disciplines, dayIdx, pairIdx, demand.disciplineId());
        penalty += weights.lateSlot() * pairIdx;

        if (room != null) {
            String[] rooms = index.groupRooms(group.id());
            for (int p = 0; p < pairsPerDay; p++) {
                String other = rooms[GenerationGrid.flat(dayIdx, p)];
                if (other != null && !other.equals(room.name())) {
                    penalty += weights.roomChange();
                    break;
                }
            }
        }
        return penalty;
    }

    /** На сколько вырастет число «окон» в этом дне, если занять слот. */
    private int gapDelta(boolean[] line, int dayIdx, int flat) {
        int before = gapsInDay(line, dayIdx);
        line[flat] = true;
        int after = gapsInDay(line, dayIdx);
        line[flat] = false;
        return after - before;
    }

    public int gapsInDay(boolean[] line, int dayIdx) {
        int first = -1;
        int last = -1;
        int busy = 0;
        for (int p = 0; p < pairsPerDay; p++) {
            if (line[GenerationGrid.flat(dayIdx, p)]) {
                if (first < 0) {
                    first = p;
                }
                last = p;
                busy++;
            }
        }
        return first < 0 ? 0 : (last - first + 1) - busy;
    }

    /**
     * Повтор рисунка недели: 0 — уникально, 1–2 — совпадения с другими днями.
     * Проверяем и «та же дисциплина на той же паре», и «та же пара дисциплин подряд».
     */
    private int patternRepeats(long[] disciplines, int dayIdx, int pairIdx, long disciplineId) {
        int repeats = 0;
        for (int d = 0; d < days; d++) {
            if (d == dayIdx) {
                continue;
            }
            if (disciplines[GenerationGrid.flat(d, pairIdx)] == disciplineId) {
                repeats++;
                break;
            }
        }
        long previous = pairIdx > 0 ? disciplines[GenerationGrid.flat(dayIdx, pairIdx - 1)] : 0L;
        if (previous != 0) {
            outer:
            for (int d = 0; d < days; d++) {
                if (d == dayIdx) {
                    continue;
                }
                for (int p = 1; p < pairsPerDay; p++) {
                    if (disciplines[GenerationGrid.flat(d, p - 1)] == previous
                            && disciplines[GenerationGrid.flat(d, p)] == disciplineId) {
                        repeats++;
                        break outer;
                    }
                }
            }
        }
        return repeats;
    }

    /** Итоговая оценка готового решения + метрики для сводки администратору. */
    public Metrics evaluate(List<SolverResult.PlacedPair> placed) {
        int slots = GenerationGrid.slotCount();
        Map<Long, boolean[]> groupLines = new HashMap<>();
        Map<Long, boolean[]> teacherLines = new HashMap<>();
        Map<Long, long[]> groupDisciplines = new HashMap<>();
        Map<Long, int[]> groupPerDay = new HashMap<>();
        Map<Long, Integer> teacherPairs = new HashMap<>();

        for (SolverResult.PlacedPair pair : placed) {
            int flat = pair.flat();
            groupLines.computeIfAbsent(pair.groupId(), k -> new boolean[slots])[flat] = true;
            teacherLines.computeIfAbsent(pair.teacherId(), k -> new boolean[slots])[flat] = true;
            groupDisciplines.computeIfAbsent(pair.groupId(), k -> new long[slots])[flat] = pair.disciplineId();
            groupPerDay.computeIfAbsent(pair.groupId(), k -> new int[days])[pair.dayIndex()]++;
            teacherPairs.merge(pair.teacherId(), 1, Integer::sum);
        }

        int groupGaps = 0;
        int teacherGaps = 0;
        int adjacentSameSubject = 0;
        int duplicateDayPatterns = 0;
        double imbalance = 0;

        for (Map.Entry<Long, boolean[]> entry : groupLines.entrySet()) {
            for (int d = 0; d < days; d++) {
                groupGaps += gapsInDay(entry.getValue(), d);
            }
            int[] perDay = groupPerDay.getOrDefault(entry.getKey(), new int[days]);
            int total = 0;
            for (int count : perDay) {
                total += count;
            }
            double target = total / (double) days;
            for (int count : perDay) {
                imbalance += sq(count - target);
            }
            long[] line = groupDisciplines.getOrDefault(entry.getKey(), new long[slots]);
            adjacentSameSubject += countAdjacentSame(line);
            duplicateDayPatterns += countDuplicateDays(line);
        }
        for (boolean[] line : teacherLines.values()) {
            for (int d = 0; d < days; d++) {
                teacherGaps += gapsInDay(line, d);
            }
        }

        double score = weights.groupGap() * groupGaps
                + weights.teacherGap() * teacherGaps
                + weights.sameSubjectAdjacent() * adjacentSameSubject
                + weights.patternRepeat() * duplicateDayPatterns
                + weights.dayImbalance() * imbalance;

        Map<String, Integer> metrics = new HashMap<>();
        metrics.put("placedPairs", placed.size());
        metrics.put("groupGaps", groupGaps);
        metrics.put("teacherGaps", teacherGaps);
        metrics.put("adjacentSameSubject", adjacentSameSubject);
        metrics.put("duplicateDayPatterns", duplicateDayPatterns);
        metrics.put("maxTeacherWeeklyHours",
                teacherPairs.values().stream().mapToInt(Integer::intValue).max().orElse(0)
                        * config.academicHoursPerPair());
        return new Metrics(score, metrics);
    }

    private int countAdjacentSame(long[] disciplines) {
        int count = 0;
        for (int d = 0; d < days; d++) {
            for (int p = 1; p < pairsPerDay; p++) {
                long previous = disciplines[GenerationGrid.flat(d, p - 1)];
                long current = disciplines[GenerationGrid.flat(d, p)];
                if (previous != 0 && previous == current) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Сколько дней недели у группы имеют полностью совпадающий набор и порядок дисциплин. */
    private int countDuplicateDays(long[] disciplines) {
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (int d = 0; d < days; d++) {
            StringBuilder signature = new StringBuilder();
            boolean empty = true;
            for (int p = 0; p < pairsPerDay; p++) {
                long value = disciplines[GenerationGrid.flat(d, p)];
                if (value != 0) {
                    empty = false;
                    signature.append(value).append('-');
                }
            }
            if (!empty && !seen.add(signature.toString())) {
                duplicates++;
            }
        }
        return duplicates;
    }

    private static double sq(double value) {
        return value * value;
    }

    public record Metrics(double penalty, Map<String, Integer> values) {
    }
}
