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

@Component
public class ScheduleSolver {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSolver.class);

    private static final int TOP_CANDIDATES = 3;

    @Value("${schedule.solver.max-millis:20000}")
    private long maxMillis = 20_000L;

    public SolverResult solve(SolverInput input) {
        return solve(input, index -> {
        });
    }

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
        retryUnplaced(index, scorer, input, placed, missing, roomsByDemand, targetPerDay, random, deadline);

        SoftScorer.Metrics metrics = scorer.evaluate(placed);
        List<SolverResult.Unplaced> unplaced = buildUnplaced(input, missing, blockStats);
        placed.sort(Comparator.comparingInt(SolverResult.PlacedPair::dayIndex)
                .thenComparingInt(SolverResult.PlacedPair::pairIndex)
                .thenComparingLong(SolverResult.PlacedPair::groupId));
        return new SolverResult(placed, unplaced, metrics.penalty(), metrics.values());
    }

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
                        break;
                    }
                    if (stats != null) {
                        stats.merge(violation, 1, Integer::sum);
                    }
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
        if (random != null) {
            java.util.Collections.shuffle(candidates, random);
        }
        candidates.sort(Comparator.comparingDouble(Placement::penalty));
        int pool = Math.min(TOP_CANDIDATES, candidates.size());
        return candidates.get(random == null ? 0 : random.nextInt(pool));
    }

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

    private void retryUnplaced(OccupancyIndex index,
                               SoftScorer scorer,
                               SolverInput input,
                               List<SolverResult.PlacedPair> placed,
                               Map<Long, Integer> missing,
                               Map<Long, List<GenerationGrid.Room>> roomsByDemand,
                               Map<Long, Double> targetPerDay,
                               Random random,
                               long deadline) {
        if (missing.isEmpty()) {
            return;
        }
        Map<Long, SolverInput.Demand> demandById = new HashMap<>();
        input.demands().forEach(d -> demandById.put(d.loadId(), d));
        Map<Long, SolverInput.GroupRef> groups = input.groupsById();
        Map<Long, SolverInput.TeacherRef> teachers = input.teachersById();

        for (Long loadId : new ArrayList<>(missing.keySet())) {
            int remaining = missing.getOrDefault(loadId, 0);
            if (remaining <= 0) {
                continue;
            }
            SolverInput.Demand demand = demandById.get(loadId);
            SolverInput.GroupRef group = demand == null ? null : groups.get(demand.groupId());
            SolverInput.TeacherRef teacher = demand == null ? null : teachers.get(demand.teacherId());
            if (demand == null || group == null || teacher == null) {
                continue;
            }
            List<GenerationGrid.Room> rooms = roomsByDemand.getOrDefault(loadId, List.of());
            double target = targetPerDay.getOrDefault(group.id(), 1.0);

            while (remaining > 0) {
                if (System.currentTimeMillis() > deadline) {
                    missing.put(loadId, remaining);
                    return;
                }
                Placement placement = bestPlacement(index, scorer, demand, group, teacher, rooms, target, random, null);
                if (placement == null) {
                    placement = tryEject(index, scorer, input, demand, group, teacher, rooms,
                            targetPerDay, placed, roomsByDemand);
                }
                if (placement == null) {
                    break;
                }
                SolverResult.PlacedPair pair = new SolverResult.PlacedPair(demand.loadId(), group.id(),
                        demand.disciplineId(), teacher.id(), placement.room().name(),
                        placement.dayIdx(), placement.pairIdx());
                index.place(pair);
                placed.add(pair);
                remaining--;
            }
            missing.put(loadId, remaining);
        }
    }

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

        int totalIterations = Math.max(1, input.config().localSearchIterations());
        final double initialTemperature = 18.0;
        final double finalTemperature = 0.05;

        for (int i = 0; i < totalIterations; i++) {
            if ((i & 255) == 0 && System.currentTimeMillis() > deadline) {
                return;
            }
            double progress = totalIterations <= 1 ? 1.0 : (double) i / (totalIterations - 1);
            double temperature = initialTemperature * Math.pow(finalTemperature / initialTemperature, progress);

            if (placed.size() > 1 && random.nextInt(50) == 0) {
                compactDayMove(index, scorer, demandById, groups, teachers, roomByName, roomsByDemand, targetPerDay,
                        placed, random, temperature);
            }
            if (placed.size() > 1 && random.nextInt(3) == 0) {
                swapMove(index, scorer, demandById, groups, teachers, roomByName, targetPerDay, placed,
                        random, temperature);
            } else {
                relocateMove(index, scorer, demandById, groups, teachers, roomByName, roomsByDemand, targetPerDay,
                        placed, random, temperature);
            }
        }
    }

    private void compactDayMove(OccupancyIndex index,
                                SoftScorer scorer,
                                Map<Long, SolverInput.Demand> demandById,
                                Map<Long, SolverInput.GroupRef> groups,
                                Map<Long, SolverInput.TeacherRef> teachers,
                                Map<String, GenerationGrid.Room> roomByName,
                                Map<Long, List<GenerationGrid.Room>> roomsByDemand,
                                Map<Long, Double> targetPerDay,
                                List<SolverResult.PlacedPair> placed,
                                Random random,
                                double temperature) {
        List<SolverResult.PlacedPair> dayPairs = null;
        int first = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            SolverResult.PlacedPair anchor = placed.get(random.nextInt(placed.size()));
            long candidateGroupId = anchor.groupId();
            int candidateDayIdx = anchor.dayIndex();
            List<SolverResult.PlacedPair> candidateDayPairs = new ArrayList<>();
            for (SolverResult.PlacedPair p : placed) {
                if (p.groupId() == candidateGroupId && p.dayIndex() == candidateDayIdx) {
                    candidateDayPairs.add(p);
                }
            }
            if (candidateDayPairs.size() < 2) {
                continue;
            }
            candidateDayPairs.sort(Comparator.comparingInt(SolverResult.PlacedPair::pairIndex));
            int candidateFirst = candidateDayPairs.get(0).pairIndex();
            int candidateSpan = candidateDayPairs.get(candidateDayPairs.size() - 1).pairIndex() - candidateFirst + 1;
            if (candidateSpan == candidateDayPairs.size()) {
                continue;
            }
            dayPairs = candidateDayPairs;
            first = candidateFirst;
            break;
        }
        if (dayPairs == null) {
            return;
        }
        long groupId = dayPairs.get(0).groupId();
        int dayIdx = dayPairs.get(0).dayIndex();

        SolverInput.GroupRef group = groups.get(groupId);
        if (group == null) {
            return;
        }
        double target = targetPerDay.getOrDefault(groupId, 1.0);

        for (SolverResult.PlacedPair p : dayPairs) {
            if (demandById.get(p.loadId()) == null || teachers.get(p.teacherId()) == null) {
                return;
            }
        }

        dayPairs.forEach(index::remove);

        double before = 0;
        for (SolverResult.PlacedPair p : dayPairs) {
            SolverInput.Demand demand = demandById.get(p.loadId());
            SolverInput.TeacherRef teacher = teachers.get(p.teacherId());
            before += scorer.placementPenalty(index, demand, group, teacher, roomByName.get(p.room()),
                    p.dayIndex(), p.pairIndex(), target);
            index.place(p);
        }
        dayPairs.forEach(index::remove);

        List<SolverResult.PlacedPair> compacted = new ArrayList<>();
        boolean ok = true;
        double after = 0;
        for (int i = 0; i < dayPairs.size(); i++) {
            SolverResult.PlacedPair original = dayPairs.get(i);
            int targetPairIdx = first + i;
            SolverInput.Demand demand = demandById.get(original.loadId());
            SolverInput.TeacherRef teacher = teachers.get(original.teacherId());
            GenerationGrid.Room preferredRoom = roomByName.get(original.room());
            GenerationGrid.Room room = preferredRoom != null
                    && index.check(demand, group, teacher, preferredRoom, dayIdx, targetPairIdx) == OccupancyIndex.Violation.NONE
                    ? preferredRoom : null;
            if (room == null) {
                for (GenerationGrid.Room candidate : roomsByDemand.getOrDefault(original.loadId(), List.of())) {
                    if (index.check(demand, group, teacher, candidate, dayIdx, targetPairIdx) == OccupancyIndex.Violation.NONE) {
                        room = candidate;
                        break;
                    }
                }
            }
            if (room == null) {
                ok = false;
                break;
            }
            after += scorer.placementPenalty(index, demand, group, teacher, room, dayIdx, targetPairIdx, target);
            SolverResult.PlacedPair moved = new SolverResult.PlacedPair(original.loadId(), original.groupId(),
                    original.disciplineId(), original.teacherId(), room.name(), dayIdx, targetPairIdx);
            index.place(moved);
            compacted.add(moved);
        }

        if (!ok) {
            compacted.forEach(index::remove);
            dayPairs.forEach(index::place);
            return;
        }

        double delta = after - before;
        boolean accept = delta < -0.001 || random.nextDouble() < Math.exp(-delta / temperature);

        if (accept) {
            for (int i = 0; i < dayPairs.size(); i++) {
                placed.remove(dayPairs.get(i));
                placed.add(compacted.get(i));
            }
        } else {
            compacted.forEach(index::remove);
            dayPairs.forEach(index::place);
        }
    }

    private void relocateMove(OccupancyIndex index,
                              SoftScorer scorer,
                              Map<Long, SolverInput.Demand> demandById,
                              Map<Long, SolverInput.GroupRef> groups,
                              Map<Long, SolverInput.TeacherRef> teachers,
                              Map<String, GenerationGrid.Room> roomByName,
                              Map<Long, List<GenerationGrid.Room>> roomsByDemand,
                              Map<Long, Double> targetPerDay,
                              List<SolverResult.PlacedPair> placed,
                              Random random,
                              double temperature) {
        int idx = random.nextInt(placed.size());
        SolverResult.PlacedPair current = placed.get(idx);
        SolverInput.Demand demand = demandById.get(current.loadId());
        SolverInput.GroupRef group = groups.get(current.groupId());
        SolverInput.TeacherRef teacher = teachers.get(current.teacherId());
        if (demand == null || group == null || teacher == null) {
            return;
        }
        double target = targetPerDay.getOrDefault(group.id(), 1.0);

        index.remove(current);
        double currentPenalty = scorer.placementPenalty(index, demand, group, teacher,
                roomByName.get(current.room()), current.dayIndex(), current.pairIndex(), target);

        Placement alternative = bestPlacement(index, scorer, demand, group, teacher,
                roomsByDemand.getOrDefault(demand.loadId(), List.of()), target, random, null);

        boolean accept;
        if (alternative == null) {
            accept = false;
        } else {
            double delta = alternative.penalty() - currentPenalty;
            accept = delta < -0.001 || random.nextDouble() < Math.exp(-delta / temperature);
        }

        if (accept) {
            SolverResult.PlacedPair moved = new SolverResult.PlacedPair(current.loadId(), current.groupId(),
                    current.disciplineId(), current.teacherId(), alternative.room().name(),
                    alternative.dayIdx(), alternative.pairIdx());
            index.place(moved);
            placed.set(idx, moved);
        } else {
            index.place(current);
        }
    }

    private void swapMove(OccupancyIndex index,
                          SoftScorer scorer,
                          Map<Long, SolverInput.Demand> demandById,
                          Map<Long, SolverInput.GroupRef> groups,
                          Map<Long, SolverInput.TeacherRef> teachers,
                          Map<String, GenerationGrid.Room> roomByName,
                          Map<Long, Double> targetPerDay,
                          List<SolverResult.PlacedPair> placed,
                          Random random,
                          double temperature) {
        int i = random.nextInt(placed.size());
        int j = random.nextInt(placed.size());
        if (i == j) {
            return;
        }
        SolverResult.PlacedPair p1 = placed.get(i);
        SolverResult.PlacedPair p2 = placed.get(j);
        SolverInput.Demand d1 = demandById.get(p1.loadId());
        SolverInput.Demand d2 = demandById.get(p2.loadId());
        SolverInput.GroupRef g1 = groups.get(p1.groupId());
        SolverInput.GroupRef g2 = groups.get(p2.groupId());
        SolverInput.TeacherRef t1 = teachers.get(p1.teacherId());
        SolverInput.TeacherRef t2 = teachers.get(p2.teacherId());
        if (d1 == null || d2 == null || g1 == null || g2 == null || t1 == null || t2 == null) {
            return;
        }
        GenerationGrid.Room room1 = roomByName.get(p1.room());
        GenerationGrid.Room room2 = roomByName.get(p2.room());
        double target1 = targetPerDay.getOrDefault(g1.id(), 1.0);
        double target2 = targetPerDay.getOrDefault(g2.id(), 1.0);

        index.remove(p1);
        index.remove(p2);

        double originalPenalty = scorer.placementPenalty(index, d1, g1, t1, room1,
                p1.dayIndex(), p1.pairIndex(), target1)
                + scorer.placementPenalty(index, d2, g2, t2, room2, p2.dayIndex(), p2.pairIndex(), target2);

        OccupancyIndex.Violation v1 = index.check(d1, g1, t1, room2, p2.dayIndex(), p2.pairIndex());
        OccupancyIndex.Violation v2 = index.check(d2, g2, t2, room1, p1.dayIndex(), p1.pairIndex());
        if (v1 != OccupancyIndex.Violation.NONE || v2 != OccupancyIndex.Violation.NONE) {
            index.place(p1);
            index.place(p2);
            return;
        }

        double swappedPenalty = scorer.placementPenalty(index, d1, g1, t1, room2,
                p2.dayIndex(), p2.pairIndex(), target1)
                + scorer.placementPenalty(index, d2, g2, t2, room1, p1.dayIndex(), p1.pairIndex(), target2);

        double delta = swappedPenalty - originalPenalty;
        boolean accept = delta < -0.001 || random.nextDouble() < Math.exp(-delta / temperature);

        if (accept) {
            SolverResult.PlacedPair moved1 = new SolverResult.PlacedPair(p1.loadId(), p1.groupId(),
                    p1.disciplineId(), p1.teacherId(), room2.name(), p2.dayIndex(), p2.pairIndex());
            SolverResult.PlacedPair moved2 = new SolverResult.PlacedPair(p2.loadId(), p2.groupId(),
                    p2.disciplineId(), p2.teacherId(), room1.name(), p1.dayIndex(), p1.pairIndex());
            index.place(moved1);
            index.place(moved2);
            placed.set(i, moved1);
            placed.set(j, moved2);
        } else {
            index.place(p1);
            index.place(p2);
        }
    }

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

    private Map<Long, List<GenerationGrid.Room>> suitableRooms(SolverInput input) {
        Map<Long, SolverInput.GroupRef> groups = input.groupsById();
        Map<Long, List<GenerationGrid.Room>> result = new HashMap<>();
        for (SolverInput.Demand demand : input.demands()) {
            SolverInput.GroupRef group = groups.get(demand.groupId());
            int students = group == null ? 0 : group.studentCount();
            List<GenerationGrid.Room> suitable = input.rooms().stream()
                    .filter(room -> room.capacity() <= 0 || students <= 0 || room.capacity() >= students)
                    .filter(room -> room.isOpenFor(demand.disciplineName()))
                    .sorted(Comparator.comparingInt(GenerationGrid.Room::capacity))
                    .toList();
            if (suitable.isEmpty()) {
                suitable = input.rooms().stream()
                        .filter(room -> room.isOpenFor(demand.disciplineName()))
                        .sorted(Comparator.comparingInt(GenerationGrid.Room::capacity).reversed())
                        .limit(3)
                        .toList();
            }
            result.put(demand.loadId(), suitable.isEmpty()
                    ? input.rooms().stream()
                            .sorted(Comparator.comparingInt(GenerationGrid.Room::capacity).reversed())
                            .limit(3)
                            .toList()
                    : suitable);
        }
        return result;
    }

    private Map<Long, Double> targetPairsPerDay(SolverInput input) {
        Map<Long, Integer> totals = new HashMap<>();
        input.demands().forEach(d -> totals.merge(d.groupId(), d.pairsPerWeek(), Integer::sum));
        Map<Long, Double> target = new HashMap<>();
        totals.forEach((groupId, total) -> target.put(groupId, total / (double) GenerationGrid.days()));
        return target;
    }

    private void registerMissing(Map<Long, Integer> missing,
                                 Map<Long, Map<SolverResult.Reason, Integer>> blockStats,
                                 long loadId, SolverResult.Reason reason) {
        missing.merge(loadId, 1, Integer::sum);
        blockStats.computeIfAbsent(loadId, k -> new EnumMap<>(SolverResult.Reason.class))
                .merge(reason, 1, Integer::sum);
    }

    private SolverResult.Reason dominantReason(Map<OccupancyIndex.Violation, Integer> stats, boolean noRooms) {
        if (noRooms) {
            return SolverResult.Reason.NO_SUITABLE_ROOM_AT_ALL;
        }
        if (stats.getOrDefault(OccupancyIndex.Violation.GROUP_WEEK_LIMIT, 0) > 0) {
            return SolverResult.Reason.GROUP_WEEK_LIMIT;
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
