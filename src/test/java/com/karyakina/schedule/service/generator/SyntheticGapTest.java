package com.karyakina.schedule.service.generator;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SyntheticGapTest {

    @Test
    void heavyLoadKeepsGroupGapsLow() {
        SolverResult result = runScenario(18, 18, new int[][]{
                {0, 1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11}, {0, 5, 9, 12, 13},
                {2, 6, 10, 14}, {3, 7, 11, 15}, {4, 8, 12, 16}, {1, 5, 13, 17},
                {0, 3, 6, 9, 12, 15}, {2, 5, 8, 11, 14, 17}
        });

        int recomputedGaps = recomputeGroupGaps(result);
        System.out.println("heavyLoad groupGaps(metrics)=" + result.metrics().get("groupGaps")
                + " recomputed=" + recomputedGaps + " placed=" + result.placed().size());

        assertTrue(recomputedGaps <= 3,
                "Слишком много окон у групп на плотной сетке: " + recomputedGaps);
    }

    @Test
    void moderateLoadReachesZeroGaps() {
        SolverResult result = runScenario(10, 15, new int[][]{
                {0, 1, 2, 3}, {4, 5, 6}, {7, 8}, {0, 4, 8, 9}
        });

        int recomputedGaps = recomputeGroupGaps(result);
        System.out.println("moderateLoad groupGaps(metrics)=" + result.metrics().get("groupGaps")
                + " recomputed=" + recomputedGaps + " placed=" + result.placed().size());

        assertTrue(recomputedGaps == 0, "При умеренной загрузке окон быть не должно: " + recomputedGaps);
    }

    private SolverResult runScenario(int groupCount, int targetPairsPerGroup, int[][] sharedGroupSets) {
        List<SolverInput.GroupRef> groups = new ArrayList<>();
        for (int g = 0; g < groupCount; g++) {
            groups.add(new SolverInput.GroupRef(g, "G" + g, 25, 5, 18, Set.of()));
        }

        List<SolverInput.TeacherRef> teachers = new ArrayList<>();
        List<SolverInput.Demand> demands = new ArrayList<>();
        long teacherId = 0;
        long loadId = 0;
        long disciplineId = 0;

        for (int[] set : sharedGroupSets) {
            long tId = teacherId++;
            teachers.add(new SolverInput.TeacherRef(tId, "SharedTeacher" + tId, 4, 18, Set.of()));
            long dId = disciplineId++;
            for (int gid : set) {
                demands.add(new SolverInput.Demand(loadId++, gid, dId, "SharedDisc" + dId, tId,
                        2, 72, Set.of(), Set.of(), null));
            }
        }

        for (int g = 0; g < groupCount; g++) {
            int gid0 = g;
            int already = Arrays.stream(sharedGroupSets).filter(set -> contains(set, gid0)).mapToInt(s -> 2).sum();
            int remaining = targetPairsPerGroup - already;
            int subjects = 6;
            for (int s = 0; s < subjects && remaining > 0; s++) {
                int pairs = Math.min(remaining, s == subjects - 1 ? remaining : 3);
                long tId = teacherId++;
                long dId = disciplineId++;
                teachers.add(new SolverInput.TeacherRef(tId, "OwnTeacher" + tId, 4, 18, Set.of()));
                demands.add(new SolverInput.Demand(loadId++, g, dId, "OwnDisc" + dId, tId,
                        pairs, pairs * 18, Set.of(), Set.of(), null));
                remaining -= pairs;
            }
        }

        List<GenerationGrid.Room> rooms = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            rooms.add(new GenerationGrid.Room("Room" + r, 30, Set.of()));
        }

        SolverInput input = new SolverInput(groups, teachers, rooms, demands, SolverConfig.defaults());
        return new ScheduleSolver().solve(input);
    }

    private int recomputeGroupGaps(SolverResult result) {
        Map<Long, Map<Integer, List<Integer>>> byGroupDay = new TreeMap<>();
        for (SolverResult.PlacedPair p : result.placed()) {
            byGroupDay.computeIfAbsent(p.groupId(), k -> new TreeMap<>())
                    .computeIfAbsent(p.dayIndex(), k -> new ArrayList<>())
                    .add(p.pairIndex());
        }
        int totalGaps = 0;
        for (var dayMap : byGroupDay.values()) {
            for (List<Integer> pairs : dayMap.values()) {
                Collections.sort(pairs);
                totalGaps += (pairs.get(pairs.size() - 1) - pairs.get(0) + 1) - pairs.size();
            }
        }
        return totalGaps;
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }
}
