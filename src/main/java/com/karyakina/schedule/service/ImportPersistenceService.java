package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Discipline;
import com.karyakina.schedule.domain.StudyGroup;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.DisciplineRepository;
import com.karyakina.schedule.repository.StudyGroupRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Отдельный бин, отвечающий исключительно за запись валидированных строк импорта в БД
 * в рамках одной транзакции. Вынесен из {@link ImportService} в отдельный класс, чтобы
 * аннотация {@code @Transactional} гарантированно перехватывалась Spring-прокси
 * (при вызове "this.метод()" внутри одного и того же бина проксирование не срабатывает).
 */
@Service
@RequiredArgsConstructor
public class ImportPersistenceService {

    private final TeacherRepository teacherRepository;
    private final DisciplineRepository disciplineRepository;
    private final StudyGroupRepository groupRepository;
    private final TeacherLoadRepository loadRepository;
    private final MonthlyRecordService monthlyRecordService;

    public static class ImportResult {
        public int processedLoads;
        public int createdTeachers;
        public int createdDisciplines;
        public int createdGroups;
        public int createdLoads;
        public int updatedLoads;
    }

    @Transactional
    public ImportResult applyRows(List<ImportService.ParsedRow> rows, Integer academicYear) {
        ImportResult result = new ImportResult();

        for (ImportService.ParsedRow row : rows) {
            Teacher teacher = teacherRepository.findByFullNameIgnoreCase(row.teacherName.trim())
                    .orElseGet(() -> {
                        result.createdTeachers++;
                        Teacher t = Teacher.builder()
                                .fullName(row.teacherName.trim())
                                .department(row.department)
                                .build();
                        return teacherRepository.save(t);
                    });

            Discipline discipline = disciplineRepository.findByNameIgnoreCase(row.disciplineName.trim())
                    .orElseGet(() -> {
                        result.createdDisciplines++;
                        Discipline d = Discipline.builder().name(row.disciplineName.trim()).build();
                        return disciplineRepository.save(d);
                    });

            StudyGroup group = groupRepository.findByNameIgnoreCase(row.groupName.trim())
                    .orElseGet(() -> {
                        result.createdGroups++;
                        StudyGroup g = StudyGroup.builder().name(row.groupName.trim()).build();
                        return groupRepository.save(g);
                    });

            int totalHours = row.totalHours != null ? row.totalHours
                    : (nz(row.hours1) + nz(row.hours2));
            int hours1 = row.hours1 != null ? row.hours1 : totalHours / 2;
            int hours2 = row.hours2 != null ? row.hours2 : (totalHours - hours1);

            TeacherLoad existing = findExistingLoad(teacher.getId(), group.getId(), discipline.getId(), academicYear);
            if (existing != null) {
                existing.setPlannedHours(totalHours);
                existing.setFirstSemesterHours(hours1);
                existing.setSecondSemesterHours(hours2);
                if (row.hoursPerWeek != null) existing.setHoursPerWeek(row.hoursPerWeek);
                if (row.lessonType != null) existing.setLessonType(row.lessonType);
                if (row.preferredDaysTime != null) existing.setPreferredDays(row.preferredDaysTime);
                if (row.controlPointType != null) existing.setControlPointType1(row.controlPointType);
                loadRepository.save(existing);
                result.updatedLoads++;
            } else {
                TeacherLoad load = TeacherLoad.builder()
                        .teacher(teacher)
                        .group(group)
                        .discipline(discipline)
                        .plannedHours(totalHours)
                        .firstSemesterHours(hours1)
                        .secondSemesterHours(hours2)
                        .readHours(0)
                        .academicYear(academicYear)
                        .hoursPerWeek(row.hoursPerWeek)
                        .lessonType(row.lessonType)
                        .preferredDays(row.preferredDaysTime)
                        .controlPointType1(row.controlPointType)
                        .overload(false)
                        .build();
                TeacherLoad savedLoad = loadRepository.save(load);
                result.createdLoads++;
                monthlyRecordService.createMonthlyRecordsForLoad(savedLoad);
            }
            result.processedLoads++;
        }
        return result;
    }

    @Transactional
    public ImportResult applyRowsWithDecisions(List<ImportService.ParsedRow> rows, Integer academicYear,
                                                java.util.Map<Integer, com.karyakina.schedule.dto.ImportRowDecisionDto> decisions,
                                                List<Teacher> allTeachers) {
        ImportResult result = new ImportResult();
        java.util.Map<Long, Teacher> teacherById = new java.util.HashMap<>();
        allTeachers.forEach(t -> teacherById.put(t.getId(), t));

        for (int i = 0; i < rows.size(); i++) {
            ImportService.ParsedRow row = rows.get(i);
            com.karyakina.schedule.dto.ImportRowDecisionDto decision = decisions != null ? decisions.get(i) : null;

            Teacher teacher = resolveTeacherForRow(row, decision, teacherById, result);

            Discipline discipline = disciplineRepository.findByNameIgnoreCase(row.disciplineName.trim())
                    .orElseGet(() -> {
                        result.createdDisciplines++;
                        Discipline d = Discipline.builder().name(row.disciplineName.trim()).build();
                        return disciplineRepository.save(d);
                    });

            StudyGroup group = groupRepository.findByNameIgnoreCase(row.groupName.trim())
                    .orElseGet(() -> {
                        result.createdGroups++;
                        StudyGroup g = StudyGroup.builder().name(row.groupName.trim()).build();
                        return groupRepository.save(g);
                    });

            int totalHours = row.totalHours != null ? row.totalHours
                    : (nz(row.hours1) + nz(row.hours2));
            int hours1 = row.hours1 != null ? row.hours1 : totalHours / 2;
            int hours2 = row.hours2 != null ? row.hours2 : (totalHours - hours1);

            TeacherLoad existing = findExistingLoad(teacher.getId(), group.getId(), discipline.getId(), academicYear);
            if (existing != null) {
                // Часы суммируются с текущей нагрузкой (тот же преподаватель мог уже вести
                // эту пару — импорт "дозаписывает" часы, а не затирает их).
                existing.setPlannedHours(nz(existing.getPlannedHours()) + totalHours);
                existing.setFirstSemesterHours(nz(existing.getFirstSemesterHours()) + hours1);
                existing.setSecondSemesterHours(nz(existing.getSecondSemesterHours()) + hours2);
                if (row.hoursPerWeek != null) existing.setHoursPerWeek(row.hoursPerWeek);
                if (row.lessonType != null) existing.setLessonType(row.lessonType);
                if (row.preferredDaysTime != null) existing.setPreferredDays(row.preferredDaysTime);
                if (row.controlPointType != null) existing.setControlPointType1(row.controlPointType);
                loadRepository.save(existing);
                result.updatedLoads++;
            } else {
                TeacherLoad load = TeacherLoad.builder()
                        .teacher(teacher)
                        .group(group)
                        .discipline(discipline)
                        .plannedHours(totalHours)
                        .firstSemesterHours(hours1)
                        .secondSemesterHours(hours2)
                        .readHours(0)
                        .academicYear(academicYear)
                        .hoursPerWeek(row.hoursPerWeek)
                        .lessonType(row.lessonType)
                        .preferredDays(row.preferredDaysTime)
                        .controlPointType1(row.controlPointType)
                        .overload(false)
                        .build();
                TeacherLoad savedLoad = loadRepository.save(load);
                result.createdLoads++;
                monthlyRecordService.createMonthlyRecordsForLoad(savedLoad);
            }
            result.processedLoads++;
        }
        return result;
    }

    /**
     * Определяет, к какому Teacher относить строку: явное решение администратора
     * (LINK/CREATE) имеет приоритет; если решения нет — старое поведение (точный поиск
     * по ФИО или создание нового).
     */
    private Teacher resolveTeacherForRow(ImportService.ParsedRow row,
                                          com.karyakina.schedule.dto.ImportRowDecisionDto decision,
                                          java.util.Map<Long, Teacher> teacherById,
                                          ImportResult result) {
        if (decision != null && "LINK".equals(decision.getAction()) && decision.getTeacherId() != null) {
            Teacher existing = teacherById.get(decision.getTeacherId());
            if (existing != null) return existing;
            return teacherRepository.findById(decision.getTeacherId())
                    .orElseThrow(() -> new RuntimeException("Преподаватель не найден: " + decision.getTeacherId()));
        }

        if (decision != null && "CREATE".equals(decision.getAction())) {
            result.createdTeachers++;
            Teacher t = Teacher.builder()
                    .fullName(row.teacherName.trim())
                    .department(decision.getDepartment() != null ? decision.getDepartment() : row.department)
                    .position(decision.getPosition())
                    .phone(decision.getPhone())
                    .build();
            Teacher saved = teacherRepository.save(t);
            teacherById.put(saved.getId(), saved);
            return saved;
        }

        // Нет явного решения — прежнее поведение: точный поиск по ФИО, иначе создать нового
        return teacherRepository.findByFullNameIgnoreCase(row.teacherName.trim())
                .orElseGet(() -> {
                    result.createdTeachers++;
                    Teacher t = Teacher.builder()
                            .fullName(row.teacherName.trim())
                            .department(row.department)
                            .build();
                    return teacherRepository.save(t);
                });
    }

    private TeacherLoad findExistingLoad(Long teacherId, Long groupId, Long disciplineId, Integer year) {
        return loadRepository.findByAcademicYear(year).stream()
                .filter(l -> l.getTeacher().getId().equals(teacherId)
                        && l.getGroup().getId().equals(groupId)
                        && l.getDiscipline().getId().equals(disciplineId))
                .findFirst()
                .orElse(null);
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
