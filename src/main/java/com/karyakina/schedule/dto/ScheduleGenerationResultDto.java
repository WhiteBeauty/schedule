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
    // Предупреждения, посчитанные ДО попытки размещения (не хватает физических
    // слотов у группы/преподавателя на заявленное число пар в неделю) — показываются
    // отдельно и первыми, чтобы администратор увидел проблему сразу, а не после
    // просмотра десятков однотипных "не удалось разместить" в conflicts.
    private List<String> capacityWarnings;
    private List<String> conflicts;
}
