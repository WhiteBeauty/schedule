package com.karyakina.schedule.service.generator;

import java.util.List;
import java.util.Map;

/**
 * Результат работы солвера. Исключений не бросает: то, что расставить не удалось,
 * лежит в {@link Unplaced} вместе с причиной — из неё сервис строит вопрос администратору.
 */
public record SolverResult(
        List<PlacedPair> placed,
        List<Unplaced> unplaced,
        double penalty,
        Map<String, Integer> metrics
) {

    /** Одна поставленная пара. */
    public record PlacedPair(
            long loadId,
            long groupId,
            long disciplineId,
            long teacherId,
            String room,
            int dayIndex,
            int pairIndex
    ) {
        public int flat() {
            return GenerationGrid.flat(dayIndex, pairIndex);
        }
    }

    /** Причина, по которой пара не встала (самая частая блокировка при переборе слотов). */
    public enum Reason {
        GROUP_BUSY("у группы нет свободных слотов в сетке"),
        TEACHER_BUSY("преподаватель занят во всех подходящих слотах"),
        TEACHER_WEEK_LIMIT("превышен недельный лимит нагрузки преподавателя"),
        TEACHER_DAY_LIMIT("превышен лимит пар в день у преподавателя"),
        GROUP_DAY_LIMIT("превышен лимит пар в день у группы"),
        GROUP_WEEK_LIMIT("превышен недельный лимит пар у группы (18)"),
        NO_ROOM("нет свободной аудитории в подходящих слотах"),
        ROOM_CAPACITY("аудитории не вмещают группу"),
        SUBJECT_LIMIT("упираемся в лимит пар дисциплины в день"),
        LUNCH("свободные слоты попадают на обед"),
        UNKNOWN("причину определить не удалось");

        private final String ru;

        Reason(String ru) {
            this.ru = ru;
        }

        public String ru() {
            return ru;
        }
    }

    public record Unplaced(
            long loadId,
            long groupId,
            long disciplineId,
            long teacherId,
            int missingPairs,
            Reason reason,
            Map<Reason, Integer> blockStats
    ) {
        public Unplaced {
            blockStats = blockStats == null ? Map.of() : Map.copyOf(blockStats);
        }
    }

    public SolverResult {
        placed = placed == null ? List.of() : List.copyOf(placed);
        unplaced = unplaced == null ? List.of() : List.copyOf(unplaced);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public static SolverResult empty() {
        return new SolverResult(List.of(), List.of(), Double.MAX_VALUE, Map.of());
    }

    public int missingPairs() {
        return unplaced.stream().mapToInt(Unplaced::missingPairs).sum();
    }

    /** Меньше нерасставленных пар важнее; при равенстве — меньше штраф мягких ограничений. */
    public boolean betterThan(SolverResult other) {
        if (other == null) {
            return true;
        }
        int mine = missingPairs();
        int theirs = other.missingPairs();
        return mine != theirs ? mine < theirs : penalty < other.penalty();
    }
}
