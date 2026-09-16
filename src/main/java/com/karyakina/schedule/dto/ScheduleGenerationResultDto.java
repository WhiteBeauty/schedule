package com.karyakina.schedule.dto;

import com.karyakina.schedule.domain.Schedule;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data @Builder
public class ScheduleGenerationResultDto {
    private int totalLoadsConsidered;
    private int placedLessons;
    private int unresolvedLoads;
    private List<Schedule> createdSchedules;
    private List<String> capacityWarnings;
    private List<String> conflicts;
}
