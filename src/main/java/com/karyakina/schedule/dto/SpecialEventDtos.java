package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Результат назначения экзамена/практики/вождения (ТЗ п.4-9) — см. SpecialEventService. */
public class SpecialEventDtos {

    @Data
    @Builder
    public static class MovedPair {
        private Long scheduleId;
        private Long teacherLoadId;
        private String disciplineName;
        private String teacherName;
        private LocalDate fromDate;
        private String fromDayOfWeek;
        private LocalTime fromStartTime;
        private LocalDate toDate;
        private String toDayOfWeek;
        private LocalTime toStartTime;
        private LocalTime toEndTime;
        private String classroom;
    }

    @Data
    @Builder
    public static class UnresolvedConflict {
        private Long teacherLoadId;
        private String disciplineName;
        private String teacherName;
        private LocalDate originalDate;
        private String originalDayOfWeek;
        private LocalTime originalStartTime;
        private String reason; // почему не удалось найти слот автоматически
    }

    @Data
    @Builder
    public static class Result {
        private Long eventId;
        private String type;
        private String groupName;
        private LocalDate startDate;
        private LocalDate endDate;
        private List<MovedPair> moved;
        private List<UnresolvedConflict> unresolved;
    }
}
