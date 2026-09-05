package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Classroom;
import com.karyakina.schedule.domain.Discipline;
import com.karyakina.schedule.domain.StudyGroup;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.ClassroomRepository;
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
    private final ClassroomRepository classroomRepository;
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
            Teacher teacher = resolveExplicitTeacherOrNull(row, result);

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

            TeacherLoad existing = findExistingLoad(
                    teacher != null ? teacher.getId() : null, group.getId(), discipline.getId(), academicYear);
            if (existing != null) {
                existing.setPlannedHours(totalHours);
                existing.setFirstSemesterHours(hours1);
                existing.setSecondSemesterHours(hours2);
                if (row.hoursPerWeek != null) existing.setHoursPerWeek(row.hoursPerWeek);
                if (row.lessonType != null) existing.setLessonType(row.lessonType);
                if (row.preferredDaysTime != null) existing.setPreferredDays(row.preferredDaysTime);
                if (row.controlPointType != null) existing.setControlPointType1(row.controlPointType);
                if (row.candidateTeachers != null) existing.setCandidateTeacherNames(row.candidateTeachers);
                loadRepository.save(existing);
                result.updatedLoads++;
            } else {
                TeacherLoad load = TeacherLoad.builder()
                        .teacher(teacher)
                        .candidateTeacherNames(row.candidateTeachers)
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

            TeacherLoad existing = findExistingLoad(
                    teacher != null ? teacher.getId() : null, group.getId(), discipline.getId(), academicYear);
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
                if (row.candidateTeachers != null) existing.setCandidateTeacherNames(row.candidateTeachers);
                loadRepository.save(existing);
                result.updatedLoads++;
            } else {
                TeacherLoad load = TeacherLoad.builder()
                        .teacher(teacher)
                        .candidateTeacherNames(row.candidateTeachers)
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

        // Строка без ФИО преподавателя и без явного решения администратора — это
        // "группа+дисциплина без преподавателя", подбор произойдёт позже, при
        // автосоставлении расписания (TeacherAssignmentService). НЕ создаём Teacher
        // с пустым ФИО.
        if (isBlank(row.teacherName)) {
            return null;
        }

        // Нет явного решения — прежнее поведение: точный поиск по ФИО, иначе создать нового.
        // Берём первого из найденных: в базе могут быть дубликаты по ФИО (без уникального
        // ограничения), и getSingleResult выбрасывает NonUniqueResultException.
        List<Teacher> existing = teacherRepository.findByFullNameIgnoreCase(row.teacherName.trim());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        result.createdTeachers++;
        Teacher t = Teacher.builder()
                .fullName(row.teacherName.trim())
                .department(row.department)
                .build();
        return teacherRepository.save(t);
    }

    /** Аналог resolveTeacherForRow для простого импорта (без экрана предпросмотра/решений). */
    private Teacher resolveExplicitTeacherOrNull(ImportService.ParsedRow row, ImportResult result) {
        if (isBlank(row.teacherName)) {
            return null;
        }
        List<Teacher> existing = teacherRepository.findByFullNameIgnoreCase(row.teacherName.trim());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        result.createdTeachers++;
        Teacher t = Teacher.builder()
                .fullName(row.teacherName.trim())
                .department(row.department)
                .build();
        return teacherRepository.save(t);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Применяет данные листа "Группы": для каждой перечисленной группы, которой ещё нет
     * в базе, создаёт запись StudyGroup (только по названию — вместимость/курс/специальность
     * заполняются администратором вручную позже). Список дисциплин из этого листа сейчас
     * не сохраняется отдельно — он используется только для того, чтобы группа появилась
     * в системе, даже если ещё ни разу не встретилась в строках основной нагрузки.
     */
    @Transactional
    public int applyGroupsSheet(java.util.Map<String, String> groupDisciplines) {
        int created = 0;
        for (String groupName : groupDisciplines.keySet()) {
            if (isBlank(groupName)) continue;
            String trimmed = groupName.trim();
            if (groupRepository.findByNameIgnoreCase(trimmed).isPresent()) continue;
            groupRepository.save(StudyGroup.builder().name(trimmed).build());
            created++;
        }
        return created;
    }

    /**
     * Применяет данные листа "Аудитории": создаёт новые аудитории или дополняет уже
     * существующие (по названию, без учёта регистра) вместимостью/списком закреплённых
     * дисциплин, если они указаны в файле. Существующие значения не затираются пустыми —
     * повторный импорт того же файла безопасен.
     */
    @Transactional
    public int applyClassrooms(List<ImportService.ParsedClassroom> rooms) {
        int created = 0;
        for (ImportService.ParsedClassroom parsed : rooms) {
            if (parsed.name() == null || parsed.name().isBlank()) continue;
            String name = parsed.name().trim();

            Classroom classroom = classroomRepository.findByNameIgnoreCase(name).orElse(null);
            boolean isNew = classroom == null;
            if (isNew) {
                classroom = Classroom.builder().name(name).allowedDisciplines(new java.util.ArrayList<>()).build();
            }

            if (parsed.capacity() != null) {
                classroom.setCapacity(parsed.capacity());
            }
            if (parsed.allowedDisciplines() != null && !parsed.allowedDisciplines().isEmpty()) {
                java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(
                        classroom.getAllowedDisciplines() == null ? List.of() : classroom.getAllowedDisciplines());
                merged.addAll(parsed.allowedDisciplines());
                classroom.setAllowedDisciplines(new java.util.ArrayList<>(merged));
            }

            classroomRepository.save(classroom);
            if (isNew) created++;
        }
        return created;
    }

    /**
     * Применяет данные второго листа импорта "Преподаватели и дисциплины": для каждого
     * найденного по ФИО преподавателя добавляет перечисленные дисциплины в его
     * specialization (без дублей), не трогая остальных. Один преподаватель может вести
     * несколько дисциплин — они перечисляются через запятую; используется модулем
     * автоподбора преподавателя (TeacherAssignmentService) при генерации расписания.
     * Преподаватели, отсутствующие в базе (ещё не встретились в основном листе и не
     * заведены вручную), молча пропускаются — этот лист только дополняет специализацию.
     */
    @Transactional
    public int applyTeacherDisciplines(java.util.Map<String, String> disciplinesByTeacherName) {
        int updated = 0;
        for (java.util.Map.Entry<String, String> entry : disciplinesByTeacherName.entrySet()) {
            String teacherName = entry.getKey();
            String disciplinesCsv = entry.getValue();
            if (isBlank(teacherName) || isBlank(disciplinesCsv)) continue;

            List<Teacher> teachers = teacherRepository.findByFullNameIgnoreCase(teacherName.trim());
            if (teachers.isEmpty()) continue;
            Teacher teacher = teachers.get(0);

            java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
            if (teacher.getSpecialization() != null && !teacher.getSpecialization().isBlank()) {
                for (String tok : teacher.getSpecialization().split(",")) {
                    String t = tok.trim();
                    if (!t.isEmpty()) merged.add(t);
                }
            }
            for (String tok : disciplinesCsv.split(",")) {
                String t = tok.trim();
                if (!t.isEmpty()) merged.add(t);
            }

            teacher.setSpecialization(String.join(", ", merged));
            teacherRepository.save(teacher);
            updated++;
        }
        return updated;
    }

    /**
     * Применяет записи, разобранные {@link TarificationImportService} из файла
     * "Тарификация" (годовая сводная таблица нагрузки по преподавателям/группам).
     *
     * В отличие от {@link #applyRows}, группы здесь ВСЕГДА создаются из файла (а не
     * сопоставляются с уже существующими в базе) — так решил администратор для этого
     * формата импорта: тарификация читается в начале учебного года и описывает состав
     * групп заново. Единственное исключение — повторный импорт того же файла: тогда
     * группа с таким же названием уже создана этим же импортом (или предыдущим его
     * запуском) и переиспользуется, чтобы не упасть на уникальном ограничении по имени.
     */
    @Transactional
    public ImportResult applyTarificationRows(List<TarificationImportService.TarificationRow> rows, Integer academicYear) {
        ImportResult result = new ImportResult();

        for (TarificationImportService.TarificationRow row : rows) {
            List<Teacher> existingTeachers = teacherRepository.findByFullNameIgnoreCase(row.teacherName.trim());
            Teacher teacher;
            if (!existingTeachers.isEmpty()) {
                teacher = existingTeachers.get(0);
            } else {
                result.createdTeachers++;
                teacher = teacherRepository.save(Teacher.builder().fullName(row.teacherName.trim()).build());
            }

            Discipline discipline = disciplineRepository.findByNameIgnoreCase(row.disciplineName.trim())
                    .orElseGet(() -> {
                        result.createdDisciplines++;
                        return disciplineRepository.save(Discipline.builder().name(row.disciplineName.trim()).build());
                    });

            StudyGroup group = groupRepository.findByNameIgnoreCase(row.groupName.trim())
                    .orElseGet(() -> {
                        result.createdGroups++;
                        return groupRepository.save(StudyGroup.builder()
                                .name(row.groupName.trim())
                                .specialty(row.specialty)
                                .course(row.course)
                                .studentCount(row.studentCount)
                                .build());
                    });

            int hours1 = nz(row.hours1);
            int hours2 = nz(row.hours2);
            int totalHours = hours1 + hours2;

            TeacherLoad existing = findExistingLoad(teacher.getId(), group.getId(), discipline.getId(), academicYear);
            if (existing != null) {
                existing.setPlannedHours(totalHours);
                existing.setFirstSemesterHours(hours1);
                existing.setSecondSemesterHours(hours2);
                if (row.control1 != null) existing.setControlPointType1(row.control1);
                if (row.control2 != null) existing.setControlPointType2(row.control2);
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
                        .controlPointType1(row.control1)
                        .controlPointType2(row.control2)
                        .readHours(0)
                        .academicYear(academicYear)
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

    private TeacherLoad findExistingLoad(Long teacherId, Long groupId, Long disciplineId, Integer year) {
        return loadRepository.findByAcademicYear(year).stream()
                .filter(l -> sameTeacher(l.getTeacher() != null ? l.getTeacher().getId() : null, teacherId)
                        && l.getGroup().getId().equals(groupId)
                        && l.getDiscipline().getId().equals(disciplineId))
                .findFirst()
                .orElse(null);
    }

    private boolean sameTeacher(Long a, Long b) {
        return a == null ? b == null : a.equals(b);
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
