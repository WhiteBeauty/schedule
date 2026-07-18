package com.karyakina.schedule.dto;

import com.karyakina.schedule.domain.Schedule;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Результат работы алгоритма автоматического составления расписания.
 * Неразрешимые конфликты (conflicts) возвращаются отдельным списком для точечной
 * ручной доработки администратором - сами занятия при этом не создаются.
 */
@Data @Builder
public class ScheduleGenerationResultDto {
    private int totalLoadsConsidered;
    private int placedLessons;
    private int unresolvedLoads;
    private List<Schedule> createdSchedules;
    private List<String> conflicts;
}
