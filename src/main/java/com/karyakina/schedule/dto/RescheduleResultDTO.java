package com.karyakina.schedule.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Итог автоматической реакции на больничный: что закрыли заменой, что перенесли,
 * что осталось на ручное решение. Возвращается администратору в интерфейсе,
 * а не только уходит в уведомления.
 */
public record RescheduleResultDTO(
        Long sickLeaveId,
        Long teacherId,
        String teacherName,
        LocalDate startDate,
        LocalDate endDate,
        int affectedLessons,
        List<Substitution> substitutions,
        List<Moved> moved,
        List<Unresolved> unresolved,
        List<MissingResourceRequest> missingData,
        List<String> warnings
) {

    /** Пара осталась на месте, преподавателя заменили. */
    public record Substitution(
            Long lessonInstanceId,
            String groupName,
            String disciplineName,
            LocalDate date,
            LocalTime startTime,
            String previousTeacherName,
            Long substituteTeacherId,
            String substituteTeacherName,
            String reason,
            boolean overload
    ) {
    }

    /** Замены не нашлось — пара перенесена в свободное «окно» группы. */
    public record Moved(
            Long lessonInstanceId,
            String groupName,
            String disciplineName,
            LocalDate fromDate,
            LocalTime fromTime,
            LocalDate toDate,
            LocalTime toTime,
            String classroom
    ) {
    }

    /** Ни замены, ни переноса — решение за администратором. */
    public record Unresolved(
            Long lessonInstanceId,
            String groupName,
            String disciplineName,
            LocalDate date,
            LocalTime startTime,
            String reason
    ) {
    }

    public RescheduleResultDTO {
        substitutions = substitutions == null ? List.of() : List.copyOf(substitutions);
        moved = moved == null ? List.of() : List.copyOf(moved);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
        missingData = missingData == null ? List.of() : List.copyOf(missingData);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static RescheduleResultDTO failed(Long teacherId, LocalDate from, LocalDate to,
                                             MissingResourceRequest problem) {
        return new RescheduleResultDTO(null, teacherId, null, from, to, 0,
                List.of(), List.of(), List.of(), List.of(problem), List.of());
    }
}
