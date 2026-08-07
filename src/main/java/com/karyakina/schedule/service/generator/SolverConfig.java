package com.karyakina.schedule.service.generator;

/**
 * Настройки автосоставления. Значения нормализуются в компактном конструкторе:
 * какие бы данные ни пришли с фронтенда, объект создаётся валидным — алгоритм не падает
 * на отрицательном или абсурдном числе.
 *
 * @param teacherMaxWeeklyHours недельный лимит нагрузки преподавателя (36 ч по требованию)
 * @param maxSameSubjectInRow   максимум одинаковых пар подряд у группы (2 => три подряд запрещены)
 * @param maxSameSubjectPerDay  максимум пар одной дисциплины у группы за день
 * @param maxPairsPerDayGroup   максимум пар в день у группы
 * @param restarts              рестартов рандомизированного поиска (побеждает лучший)
 * @param localSearchIterations итераций локального улучшения на каждый рестарт
 */
public record SolverConfig(
        int academicHoursPerPair,
        int teacherMaxWeeklyHours,
        int teacherDefaultMaxPairsPerDay,
        int maxSameSubjectInRow,
        int maxSameSubjectPerDay,
        int maxPairsPerDayGroup,
        int restarts,
        int localSearchIterations,
        long randomSeed,
        Weights weights
) {

    /** Веса мягких ограничений: чем больше, тем сильнее алгоритм избегает ситуации. */
    public record Weights(
            double groupGap,             // «окно» у группы
            double teacherGap,           // «окно» у преподавателя
            double sameSubjectAdjacent,  // защита от переутомления: две одинаковые пары подряд
            double dayImbalance,         // неравномерность нагрузки по дням недели
            double patternRepeat,        // повтор рисунка дня (во вторник то же и в том же порядке, что в понедельник)
            double preferredDayMiss,     // пожелания преподавателя по дням не учтены
            double lateSlot,             // поздние пары
            double roomChange            // переходы группы между аудиториями внутри дня
    ) {
        public static Weights defaults() {
            return new Weights(30, 12, 60, 6, 20, 8, 1.5, 2);
        }
    }

    public SolverConfig {
        academicHoursPerPair = clamp(academicHoursPerPair, 1, 4);
        teacherMaxWeeklyHours = clamp(teacherMaxWeeklyHours, 2, 200);
        teacherDefaultMaxPairsPerDay = clamp(teacherDefaultMaxPairsPerDay, 1, GenerationGrid.pairsPerDay());
        maxSameSubjectInRow = clamp(maxSameSubjectInRow, 1, GenerationGrid.pairsPerDay());
        maxSameSubjectPerDay = clamp(maxSameSubjectPerDay, 1, GenerationGrid.pairsPerDay());
        maxPairsPerDayGroup = clamp(maxPairsPerDayGroup, 1, GenerationGrid.pairsPerDay());
        restarts = clamp(restarts, 1, 100);
        localSearchIterations = clamp(localSearchIterations, 0, 200_000);
        if (weights == null) {
            weights = Weights.defaults();
        }
    }

    public static SolverConfig defaults() {
        return new SolverConfig(2, 36, 4, 2, 2, 5, 10, 5000, 20260501L, Weights.defaults());
    }

    /** 36 часов / 2 часа в паре = 18 пар в неделю. */
    public int teacherMaxWeeklyPairs() {
        return Math.max(1, teacherMaxWeeklyHours / academicHoursPerPair);
    }

    /** Часы -> пары, с округлением вверх (11 часов при паре в 2 часа = 6 пар). */
    public int hoursToPairs(int academicHours) {
        return (Math.max(0, academicHours) + academicHoursPerPair - 1) / academicHoursPerPair;
    }

    public int pairsToHours(int pairs) {
        return Math.max(0, pairs) * academicHoursPerPair;
    }

    public SolverConfig withSeed(long seed) {
        return new SolverConfig(academicHoursPerPair, teacherMaxWeeklyHours, teacherDefaultMaxPairsPerDay,
                maxSameSubjectInRow, maxSameSubjectPerDay, maxPairsPerDayGroup, restarts, localSearchIterations,
                seed, weights);
    }

    public SolverConfig with(Integer maxPairsPerDayGroupOverride,
                             Integer teacherMaxWeeklyHoursOverride,
                             Integer maxSameSubjectInRowOverride,
                             Integer maxSameSubjectPerDayOverride,
                             Integer restartsOverride) {
        return new SolverConfig(
                academicHoursPerPair,
                teacherMaxWeeklyHoursOverride == null ? teacherMaxWeeklyHours : teacherMaxWeeklyHoursOverride,
                teacherDefaultMaxPairsPerDay,
                maxSameSubjectInRowOverride == null ? maxSameSubjectInRow : maxSameSubjectInRowOverride,
                maxSameSubjectPerDayOverride == null ? maxSameSubjectPerDay : maxSameSubjectPerDayOverride,
                maxPairsPerDayGroupOverride == null ? maxPairsPerDayGroup : maxPairsPerDayGroupOverride,
                restartsOverride == null ? restarts : restartsOverride,
                localSearchIterations, randomSeed, weights);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
