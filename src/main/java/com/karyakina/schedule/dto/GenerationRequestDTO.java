package com.karyakina.schedule.dto;

import java.util.List;

public record GenerationRequestDTO(
        Integer academicYear,
        List<Long> groupIds,
        List<ManualLoadEntry> manualLoad,
        GridSettings grid,
        boolean persist,
        Integer semester
) {

    public record ManualLoadEntry(
            Long teacherLoadId,
            Long groupId,
            String groupName,
            Long disciplineId,
            String disciplineName,
            Long teacherId,
            Integer academicHours,
            Integer hoursPerWeek
    ) {
    }

    public record GridSettings(
            Integer pairsPerDay,
            Integer workingDays,
            Integer maxPairsPerDayGroup,
            Integer maxSameSubjectInRow,
            Integer maxSameSubjectPerDay,
            Integer restarts,
            Integer maxWeeklyHoursPerSubjectPerGroup
    ) {
    }

    public GenerationRequestDTO {
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        manualLoad = manualLoad == null ? List.of() : List.copyOf(manualLoad);
    }

    public static GenerationRequestDTO forYear(Integer academicYear, boolean persist) {
        return new GenerationRequestDTO(academicYear, List.of(), List.of(), null, persist, null);
    }
}
