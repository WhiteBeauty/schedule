package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.dto.GenerationRequestDTO;
import com.karyakina.schedule.dto.GenerationResultDTO;
import com.karyakina.schedule.dto.MissingResourceRequest;
import com.karyakina.schedule.dto.PlannedLessonDto;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class VacancyService {

    private final TeacherLoadRepository loadRepository;
    private final TeacherRepository teacherRepository;
    private final ScheduleGeneratorService generatorService;
    private final AdminDeletionService adminDeletionService;

    public record Placement(int semester, int pairsPerWeek, int missingPairs, boolean rebuilt, List<String> notes) {
    }

    public record AssignResult(Long loadId, String teacherName, int academicYear, boolean practice,
                               List<Placement> placements, Set<Long> groupLoadIds) {
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(int academicYear) {
        List<TeacherLoad> loads = loadRepository.findByAcademicYear(academicYear);
        Map<Long, Set<Long>> teachersByDiscipline = new HashMap<>();
        for (TeacherLoad l : loads) {
            if (l.getTeacher() != null && l.getDiscipline() != null) {
                teachersByDiscipline.computeIfAbsent(l.getDiscipline().getId(), k -> new TreeSet<>())
                        .add(l.getTeacher().getId());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        loads.stream()
                .filter(l -> l.getTeacher() == null && !Boolean.TRUE.equals(l.getOverload()))
                .filter(l -> l.getGroup() != null && l.getDiscipline() != null)
                .sorted(Comparator.comparing((TeacherLoad l) -> ScheduleGeneratorService.isPracticeOrDriving(
                                l.getDiscipline().getName()))
                        .thenComparing(l -> l.getGroup().getName())
                        .thenComparing(l -> l.getDiscipline().getName()))
                .forEach(l -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("loadId", l.getId());
                    row.put("disciplineName", l.getDiscipline().getName());
                    row.put("groupId", l.getGroup().getId());
                    row.put("groupName", l.getGroup().getName());
                    row.put("firstSemesterHours", l.getFirstSemesterHours());
                    row.put("secondSemesterHours", l.getSecondSemesterHours());
                    row.put("practice", ScheduleGeneratorService.isPracticeOrDriving(l.getDiscipline().getName()));
                    row.put("suggestedTeacherIds", new ArrayList<>(
                            teachersByDiscipline.getOrDefault(l.getDiscipline().getId(), Set.of())));
                    rows.add(row);
                });
        return rows;
    }

    @Transactional
    public AssignResult assign(Long loadId, Long teacherId, String adminName) {
        TeacherLoad load = loadRepository.findById(loadId)
                .orElseThrow(() -> new IllegalArgumentException("Нагрузка не найдена"));
        if (load.getTeacher() != null) {
            throw new IllegalArgumentException("У этой нагрузки уже есть преподаватель: " + load.getTeacher().getFullName());
        }
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new IllegalArgumentException("Преподаватель не найден"));
        load.setTeacher(teacher);
        loadRepository.save(load);
        log.info("Вакансия {} / {} отдана преподавателю {}", load.getDiscipline().getName(), load.getGroup().getName(),
                teacher.getFullName());

        boolean practice = ScheduleGeneratorService.isPracticeOrDriving(load.getDiscipline().getName());
        List<Placement> placements = new ArrayList<>();
        if (!practice) {
            for (int semester = 1; semester <= 2; semester++) {
                int hours = semester == 2 ? nz(load.getSecondSemesterHours()) : nz(load.getFirstSemesterHours());
                if (hours <= 0 || LocalDate.now().isAfter(AcademicYearUtil.semesterEnd(semester, load.getAcademicYear()))) {
                    continue;
                }
                placements.add(place(load, semester, adminName));
            }
        }
        Set<Long> groupLoadIds = new TreeSet<>();
        loadRepository.findByGroupId(load.getGroup().getId()).forEach(l -> groupLoadIds.add(l.getId()));
        return new AssignResult(load.getId(), teacher.getFullName(), load.getAcademicYear(), practice, placements,
                groupLoadIds);
    }

    // сначала пробуем дописать пары в свободные места а если не влезли собираем неделю группы заново
    private Placement place(TeacherLoad load, int semester, String adminName) {
        Long groupId = load.getGroup().getId();
        GenerationRequestDTO request = new GenerationRequestDTO(load.getAcademicYear(),
                load.getTarification() != null ? load.getTarification().getId() : null,
                List.of(groupId), List.of(), null, false, semester);

        GenerationResultDTO fit = generatorService.generateInteractive(request, adminName, Set.of());
        int fitMissing = missing(fit);
        if (!fit.hasBlocking() && fitMissing == 0) {
            return save(fit, load, semester, false, 0, adminName);
        }

        GenerationResultDTO rebuild = generatorService.generateInteractive(request, adminName, Set.of(groupId));
        int rebuildMissing = missing(rebuild);
        if (!rebuild.hasBlocking() && !rebuild.successSchedule().isEmpty()
                && (fit.hasBlocking() || rebuildMissing < fitMissing)) {
            int removed = adminDeletionService.deleteGroupWeek(groupId, load.getAcademicYear(), semester);
            log.info("Неделя группы {} в {} семестре собирается заново: снято {} пар", load.getGroup().getName(),
                    semester, removed);
            return save(rebuild, load, semester, true, rebuildMissing, adminName);
        }
        if (!fit.hasBlocking()) {
            return save(fit, load, semester, false, fitMissing, adminName);
        }
        return new Placement(semester, 0, -1, false, problems(fit));
    }

    private Placement save(GenerationResultDTO result, TeacherLoad load, int semester, boolean rebuilt,
                           int missingPairs, String adminName) {
        if (result.successSchedule().isEmpty()) {
            generatorService.rememberUnplaced(result.sessionId());
            return new Placement(semester, 0, missingPairs, false, problems(result));
        }
        GenerationResultDTO saved = saved(generatorService.commit(result.sessionId(), adminName));
        return new Placement(semester, pairsOf(saved, load), missingPairs, rebuilt, problems(result));
    }

    private static GenerationResultDTO saved(GenerationResultDTO result) {
        if (!result.persisted()) {
            throw new IllegalStateException(result.missingData().isEmpty()
                    ? "расписание не сохранилось" : result.missingData().get(0).message());
        }
        return result;
    }

    private static int missing(GenerationResultDTO result) {
        return result.metrics() == null ? 0 : result.metrics().getOrDefault("missingPairs", 0);
    }

    private static int pairsOf(GenerationResultDTO result, TeacherLoad load) {
        int count = 0;
        for (PlannedLessonDto lesson : result.successSchedule()) {
            if (load.getId().equals(lesson.teacherLoadId())) count++;
        }
        return count;
    }

    private static List<String> problems(GenerationResultDTO result) {
        List<String> notes = new ArrayList<>();
        for (MissingResourceRequest issue : result.missingData()) {
            if (issue.code() == MissingResourceRequest.Code.UNPLACED_LESSONS
                    || issue.severity() == MissingResourceRequest.Severity.BLOCKING) {
                notes.add(issue.message());
            }
        }
        return notes;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
