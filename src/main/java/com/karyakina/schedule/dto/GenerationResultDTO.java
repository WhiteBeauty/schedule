package com.karyakina.schedule.dto;

import java.util.List;
import java.util.Map;

public record GenerationResultDTO(
        String sessionId,
        Status status,
        boolean persisted,
        List<PlannedLessonDto> successSchedule,
        List<MissingResourceRequest> missingData,
        List<String> warnings,
        Map<String, Integer> metrics
) {

    public enum Status {
        OK,
        NEEDS_INPUT,
        PARTIAL,
        FAILED
    }

    public GenerationResultDTO {
        successSchedule = successSchedule == null ? List.of() : List.copyOf(successSchedule);
        missingData = missingData == null ? List.of() : List.copyOf(missingData);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public static GenerationResultDTO failed(String sessionId, MissingResourceRequest problem) {
        return new GenerationResultDTO(sessionId, Status.FAILED, false, List.of(), List.of(problem),
                List.of(), Map.of());
    }

    public boolean hasBlocking() {
        return missingData.stream().anyMatch(m -> m.severity() == MissingResourceRequest.Severity.BLOCKING);
    }
}
