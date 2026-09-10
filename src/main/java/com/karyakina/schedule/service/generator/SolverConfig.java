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
 * @param maxWeeklyHoursPerSubjectPerGroup максимум часов ОДНОГО предмета в неделю у ОДНОЙ
 *        группы (независимо от суммарной недельной нагрузки преподавателя — это отдельное,
 *        более узкое ограничение: даже если у препода в целом часов в пределах нормы, не
 *        должно быть так, что одна группа получает, например, 20 ч одного и того же предмета
 *        в неделю). Не применяется, если часы утверждены администратором вручную как
 *        сознательный "интенсив"/практика блоками — тогда выдаётся только предупреждение,
 *        которое можно принять как есть.
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
        int maxWeeklyHoursPerSubjectPerGroup,
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
            // groupGap/teacherGap подняты (30->55, 12->22) после перехода учебной недели
            // с 6 дней на 5 (суббота больше не рабочий день) — то же количество пар теперь
            // нужно уместить в меньшее число дней, и без более сильного приоритета против
            // "окон" локальный поиск охотнее принимал разрывы ради других, более мягких
            // целей (баланс по дням, избегание повторов рисунка дня и т.п.).
            return new Weights(55, 22, 60, 6, 20, 8, 1.5, 2);
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
        // restarts подняты с 10 до 30 — после перехода на сетку из 5 "настоящих" пар в день
        // (было по ошибке 10 условных получасовых пар) свободных слотов физически вдвое
        // меньше, и жадному алгоритму с малым числом попыток стало заметно чаще не хватать
        // рестартов, чтобы найти решение, которое при этом физически существует (проявлялось
        // как "не встали пары", хотя вручную то же место вставало без проблем). Жёсткий
        // потолок по времени (20 сек, см. ScheduleSolver.maxMillis) не даёт этому раздуть
        // время генерации — просто использует бюджет времени эффективнее.
        // localSearchIterations подняты 5000->9000 вместе с усиленными весами за "окна"
        // (см. Weights.defaults) — раз с 6 дней перешли на 5 (суббота не учебный день),
        // местного поиска на прежнем бюджете итераций стало не хватать, чтобы реально
        // выбрать разрывы за счёт более мягких целей.
        return new SolverConfig(2, 36, 4, 2, 2, 5, 8, 30, 9000, 20260501L, Weights.defaults());
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
