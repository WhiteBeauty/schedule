package com.karyakina.schedule.dto;

import java.util.List;
import java.util.Map;

/**
 * Единственный формат ответа автосоставления. Пустых ошибок и 500-х не бывает:
 * всё, что пошло не так, лежит в {@code missingData} с готовыми вариантами действий
 * либо в {@code warnings}.
 *
 * @param sessionId       идентификатор сессии — с ним отправляются решения администратора
 *                        (POST /api/schedule-generation/{sessionId}/resolve) и фиксация результата
 * @param status          OK / NEEDS_INPUT / PARTIAL / FAILED
 * @param successSchedule то, что удалось расставить (черновик или уже сохранённые пары)
 * @param missingData     вопросы администратору с вариантами действий
 * @param warnings        предупреждения, не требующие ответа
 *                        («У преподавателя Иванов И.И. осталось 2 нераспределённых часа нагрузки»)
 * @param metrics         сводка: окна, число пар, максимальная недельная нагрузка и т.д.
 */
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
        /** Всё расставлено, вопросов нет. */
        OK,
        /** Есть блокирующие вопросы — после ответа генерация повторится. */
        NEEDS_INPUT,
        /** Часть пар не встала, блокирующих вопросов нет. */
        PARTIAL,
        /** Техническая ошибка — причина в missingData, интерфейс покажет текст. */
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
