package com.karyakina.schedule.dto;

import java.util.List;

/**
 * Параметры запуска автосоставления. Все поля необязательны — отсутствие данных
 * не должно ронять запрос, пустые значения заменяются умолчаниями.
 *
 * @param manualLoad часы, введённые руками в интерфейсе. Именно они сверяются с часами
 *                   из файла нагрузки (TeacherLoad.plannedHours / hoursPerWeek);
 *                   расхождение превращается в вопрос администратору, а не в исключение
 * @param semester   1 или 2 — на какой семестр считать часы в неделю (берутся
 *                   firstSemesterHours/secondSemesterHours соответственно, а не сумма
 *                   за год). Null — определяется автоматически по текущей дате
 *                   (см. AcademicYearUtil.getCurrentSemester()).
 */
public record GenerationRequestDTO(
        Integer academicYear,
        List<Long> groupIds,
        List<ManualLoadEntry> manualLoad,
        GridSettings grid,
        boolean persist,
        Integer semester
) {

    /** Одна строка ручного ввода плана: столько-то часов дисциплины у группы. */
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

    /** Настройки сетки с фронтенда. null у поля = «оставить значение по умолчанию». */
    public record GridSettings(
            Integer pairsPerDay,
            Integer workingDays,
            Integer maxPairsPerDayGroup,
            Integer teacherMaxWeeklyHours,
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
