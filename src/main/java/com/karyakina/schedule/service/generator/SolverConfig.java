package com.karyakina.schedule.service.generator;

public record SolverConfig(
        int academicHoursPerPair,
        int teacherMaxWeeklyHours,
        int teacherDefaultMaxPairsPerDay,
        int maxSameSubjectInRow,
        int maxSameSubjectPerDay,
        int maxPairsPerDayGroup,
        int maxWeeklyHoursPerSubjectPerGroup,
        int restarts,
        int localSearchIterations,
        long randomSeed,
        Weights weights
) {

    public record Weights(
            double groupGap,
            double teacherGap,
            double sameSubjectAdjacent,
            double dayImbalance,
            double patternRepeat,
            double preferredDayMiss,
            double lateSlot,
            double roomChange
    ) {
        public static Weights defaults() {
            return new Weights(38, 16, 60, 10, 20, 8, 1.5, 2);
        }
    }

    public SolverConfig {
        academicHoursPerPair = clamp(academicHoursPerPair, 1, 4);
        teacherMaxWeeklyHours = clamp(teacherMaxWeeklyHours, 2, 200);
        teacherDefaultMaxPairsPerDay = clamp(teacherDefaultMaxPairsPerDay, 1, GenerationGrid.pairsPerDay());
        maxSameSubjectInRow = clamp(maxSameSubjectInRow, 1, GenerationGrid.pairsPerDay());
        maxSameSubjectPerDay = clamp(maxSameSubjectPerDay, 1, GenerationGrid.pairsPerDay());
        maxPairsPerDayGroup = clamp(maxPairsPerDayGroup, 1, GenerationGrid.pairsPerDay());
        maxWeeklyHoursPerSubjectPerGroup = clamp(maxWeeklyHoursPerSubjectPerGroup, 2, 200);
        restarts = clamp(restarts, 1, 100);
        localSearchIterations = clamp(localSearchIterations, 0, 200_000);
        if (weights == null) {
            weights = Weights.defaults();
        }
    }

    public static SolverConfig defaults() {
        return new SolverConfig(2, 36, 4, 2, 2, 5, 8, 30, 9000, 20260501L, Weights.defaults());
    }

    public int teacherMaxWeeklyPairs() {
        return Math.max(1, teacherMaxWeeklyHours / academicHoursPerPair);
    }

    public int hoursToPairs(int academicHours) {
        return (Math.max(0, academicHours) + academicHoursPerPair - 1) / academicHoursPerPair;
    }

    public int pairsToHours(int pairs) {
        return Math.max(0, pairs) * academicHoursPerPair;
    }

    public SolverConfig withSeed(long seed) {
        return new SolverConfig(academicHoursPerPair, teacherMaxWeeklyHours, teacherDefaultMaxPairsPerDay,
                maxSameSubjectInRow, maxSameSubjectPerDay, maxPairsPerDayGroup, maxWeeklyHoursPerSubjectPerGroup,
                restarts, localSearchIterations, seed, weights);
    }

    public SolverConfig with(Integer maxPairsPerDayGroupOverride,
                             Integer teacherMaxWeeklyHoursOverride,
                             Integer maxSameSubjectInRowOverride,
                             Integer maxSameSubjectPerDayOverride,
                             Integer restartsOverride,
                             Integer maxWeeklyHoursPerSubjectPerGroupOverride) {
        return new SolverConfig(
                academicHoursPerPair,
                teacherMaxWeeklyHoursOverride == null ? teacherMaxWeeklyHours : teacherMaxWeeklyHoursOverride,
                teacherDefaultMaxPairsPerDay,
                maxSameSubjectInRowOverride == null ? maxSameSubjectInRow : maxSameSubjectInRowOverride,
                maxSameSubjectPerDayOverride == null ? maxSameSubjectPerDay : maxSameSubjectPerDayOverride,
                maxPairsPerDayGroupOverride == null ? maxPairsPerDayGroup : maxPairsPerDayGroupOverride,
                maxWeeklyHoursPerSubjectPerGroupOverride == null
                        ? maxWeeklyHoursPerSubjectPerGroup : maxWeeklyHoursPerSubjectPerGroupOverride,
                restartsOverride == null ? restarts : restartsOverride,
                localSearchIterations, randomSeed, weights);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
