package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.Notification;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.SickLeave;
import com.karyakina.schedule.domain.SubstitutionRequest;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.dto.MissingResourceRequest;
import com.karyakina.schedule.dto.RescheduleResultDTO;
import com.karyakina.schedule.repository.ClassroomRepository;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.SickLeaveRepository;
import com.karyakina.schedule.repository.SubstitutionRequestRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class SickLeaveReschedulingService {

    private final SickLeaveRepository sickLeaveRepository;
    private final TeacherRepository teacherRepository;
    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;
    private final ClassroomRepository classroomRepository;
    private final LessonInstanceRepository lessonInstanceRepository;
    private final SubstitutionRequestRepository substitutionRequestRepository;
    private final LessonInstanceService lessonInstanceService;
    private final NotificationService notificationService;
    private final CalendarSlotFinder slotFinder;
    private final GroupWindowService groupWindowService;
    private final HoursRecalculationService hoursRecalculationService;
    private final SettingsService settingsService;

    @Transactional
    public RescheduleResultDTO handleTeacherSickLeave(Long teacherId, LocalDate startDate, LocalDate endDate) {
        try {
            if (teacherId == null || startDate == null || endDate == null) {
                return RescheduleResultDTO.failed(teacherId, startDate, endDate, MissingResourceRequest.technical(
                        "Не указан преподаватель или период отсутствия."));
            }
            if (endDate.isBefore(startDate)) {
                LocalDate swap = startDate;
                startDate = endDate;
                endDate = swap;
            }
            Teacher teacher = teacherRepository.findById(teacherId).orElse(null);
            if (teacher == null) {
                return RescheduleResultDTO.failed(teacherId, startDate, endDate, MissingResourceRequest.technical(
                        "Преподаватель #" + teacherId + " не найден."));
            }

            SickLeave sickLeave = findOrCreateSickLeave(teacher, startDate, endDate);

            List<LessonInstance> affected = collectAffectedLessons(teacher, sickLeave, startDate, endDate);
            List<RescheduleResultDTO.Substitution> substitutions = new ArrayList<>();
            List<RescheduleResultDTO.Moved> moved = new ArrayList<>();
            List<RescheduleResultDTO.Unresolved> unresolved = new ArrayList<>();
            List<MissingResourceRequest> missing = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            int year = sickLeave.getAcademicYear();
            CalendarSlotFinder.Calendar calendar = slotFinder.open(year, startDate, AcademicYearUtil.semesterEnd(2, year));
            Pool pool = new Pool(loadRepository.findByAcademicYear(year), teacherRepository.findAll());
            Map<Long, Set<LocalDate>> holes = new LinkedHashMap<>();
            for (LessonInstance instance : affected) {
                try {
                    Candidate substitute = findBestSubstitute(instance, teacher, calendar, pool);
                    if (substitute != null) {
                        applySubstitution(instance, sickLeave, substitute, substitutions);
                        occupyForSubstitute(calendar, instance, substitute.teacher());
                        if (substitute.overload()) {
                            warnings.add("Замену на " + instance.getLessonDate() + " ведёт "
                                    + substitute.teacher().getFullName()
                                    + " сверх плановой нагрузки, часы уйдут в переработку.");
                        }
                        continue;
                    }
                    String[] failure = new String[1];
                    RescheduleResultDTO.Moved move = tryMoveToFreeSlot(instance, sickLeave, calendar, failure);
                    if (move != null) {
                        moved.add(move);
                        if (failure[0] != null) {
                            warnings.add(failure[0]);
                        }
                        TeacherLoad movedLoad = instance.getSchedule().getTeacherLoad();
                        if (movedLoad != null && movedLoad.getGroup() != null) {
                            holes.computeIfAbsent(movedLoad.getGroup().getId(), k -> new java.util.TreeSet<>())
                                    .add(instance.getLessonDate());
                        }
                        continue;
                    }
                    unresolved.add(describeUnresolved(instance, failure[0]));
                    missing.add(unresolvedIssue(instance, teacher));
                } catch (Exception perLesson) {
                    log.error("Ошибка обработки пары {} при больничном преподавателя {}",
                            instance.getId(), teacherId, perLesson);
                    warnings.add("Пара " + instance.getLessonDate() + ": обработать автоматически не удалось ("
                            + perLesson.getClass().getSimpleName() + "), требуется ручная проверка.");
                    unresolved.add(describeUnresolved(instance, null));
                }
            }

            GroupWindowService.Outcome windows = groupWindowService.closeWindows(holes, sickLeave.getAcademicYear(),
                    sickLeave.getId());
            if (windows.shiftedInDay() > 0) {
                warnings.add("Чтобы у групп не было окон в дни отсутствия, сдвинуто пар внутри дня: "
                        + windows.shiftedInDay() + ".");
            }
            warnings.addAll(windows.warnings());

            notifySummary(teacher, sickLeave, affected.size(), substitutions.size(), moved.size(), unresolved.size());
            log.info("Отсутствие {} ({} — {}): затронуто {}, замен {}, переносов {}, не решено {}, предупреждений {}",
                    teacher.getFullName(), startDate, endDate, affected.size(), substitutions.size(), moved.size(),
                    unresolved.size(), warnings.size());
            unresolved.forEach(u -> log.info("Отсутствие {}: не решена пара {}", teacher.getFullName(), u));
            warnings.forEach(w -> log.info("Отсутствие {}: {}", teacher.getFullName(), w));

            if (!unresolved.isEmpty() && settingsService.isPaceRecalculationEnabled()) {
                try {
                    java.util.Set<Long> loadIds = new java.util.HashSet<>();
                    java.util.Set<Integer> semesters = new java.util.HashSet<>();
                    for (LessonInstance instance : affected) {
                        if (instance.getStatus() == LessonInstance.Status.PLANNED && instance.getSchedule() != null
                                && instance.getSchedule().getTeacherLoad() != null) {
                            loadIds.add(instance.getSchedule().getTeacherLoad().getId());
                            semesters.add(AcademicYearUtil.semesterOfDate(instance.getLessonDate()));
                        }
                    }
                    for (int sem : semesters) {
                        if (sem == 0) continue;
                        HoursRecalculationService.Result r = hoursRecalculationService.recalculate(
                                sickLeave.getAcademicYear(), sem, loadIds, false);
                        if (!r.added().isEmpty()) {
                            warnings.add("Для восполнения пропущенных часов добавлено пар: " + r.added().size() + ".");
                        }
                    }
                } catch (Exception recalcEx) {
                    log.warn("Пересчёт часов после отсутствия не выполнен: {}", recalcEx.getMessage());
                }
            }

            return new RescheduleResultDTO(sickLeave.getId(), teacher.getId(), teacher.getFullName(),
                    startDate, endDate, affected.size(), substitutions, moved, unresolved, missing, warnings);

        } catch (Exception e) {
            log.error("Не удалось обработать больничный преподавателя {}", teacherId, e);
            return RescheduleResultDTO.failed(teacherId, startDate, endDate, MissingResourceRequest.technical(
                    "Обработка отсутствия прервана: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage())
                            + ". Расписание не изменено, требуется ручная проверка."));
        }
    }

    @Transactional
    public int revertSickLeave(Long sickLeaveId) {
        int reverted = 0;
        for (SubstitutionRequest request : substitutionRequestRepository.findBySickLeaveId(sickLeaveId)) {
            LessonInstance li = request.getLessonInstance();
            if (li != null && li.getStatus() == LessonInstance.Status.REPLACED) {
                lessonInstanceService.revertReplacement(li.getId(), "Отмена замены: отсутствие удалено");
                reverted++;
            }
        }
        for (Schedule row : scheduleRepository.findBySickLeaveId(sickLeaveId)) {
            for (LessonInstance li : lessonInstanceRepository.findByScheduleIdIn(List.of(row.getId()))) {
                lessonInstanceService.revertHoursBeforeDelete(li, "Отмена переноса: отсутствие удалено");
                substitutionRequestRepository.deleteByLessonInstanceIdIn(List.of(li.getId()));
                lessonInstanceRepository.delete(li);
            }
            scheduleRepository.delete(row);
            reverted++;
        }
        for (LessonInstance li : lessonInstanceRepository.findByCancelledBySickLeaveId(sickLeaveId)) {
            li.setStatus(LessonInstance.Status.PLANNED);
            li.setCancelledAt(null);
            li.setNote(null);
            li.setCancelledBySickLeaveId(null);
            lessonInstanceRepository.save(li);
            reverted++;
        }
        return reverted;
    }

    private record Candidate(Teacher teacher, int priorityRank, String reason, boolean overload, int dayLoad,
                             int remainingHours) {
    }

    private record Pool(List<TeacherLoad> loads, List<Teacher> teachers) {
    }

    // сначала та же дисциплина потом смежная потом любой свободный
    private Candidate findBestSubstitute(LessonInstance instance, Teacher sickTeacher,
                                         CalendarSlotFinder.Calendar calendar, Pool pool) {
        TeacherLoad originalLoad = instance.getSchedule().getTeacherLoad();
        if (originalLoad == null || originalLoad.getDiscipline() == null) {
            return null;
        }
        LocalDate date = instance.getLessonDate();
        int pairIdx = GenerationGrid.pairIndex(instance.getSchedule().getStartTime());
        String discipline = originalLoad.getDiscipline().getName();
        Long disciplineId = originalLoad.getDiscipline().getId();
        String specialty = originalLoad.getGroup() != null ? originalLoad.getGroup().getSpecialty() : null;
        Long groupId = originalLoad.getGroup() != null ? originalLoad.getGroup().getId() : null;

        Map<Long, List<TeacherLoad>> loadsByTeacher = new java.util.HashMap<>();
        for (TeacherLoad l : pool.loads()) {
            if (l.getTeacher() != null && !Boolean.TRUE.equals(l.getOverload())) {
                loadsByTeacher.computeIfAbsent(l.getTeacher().getId(), k -> new ArrayList<>()).add(l);
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Teacher candidate : pool.teachers()) {
            if (candidate.getId().equals(sickTeacher.getId())) continue;
            if (!isFree(candidate, instance, date, pairIdx, calendar)) continue;
            List<TeacherLoad> own = loadsByTeacher.getOrDefault(candidate.getId(), List.of());
            int remaining = own.stream().mapToInt(this::safeRemainingHours).sum();
            int dayLoad = calendar.teacherPairsOn(candidate.getId(), date);

            TeacherLoad sameDiscipline = own.stream()
                    .filter(l -> l.getDiscipline() != null && l.getDiscipline().getId().equals(disciplineId))
                    .findFirst().orElse(null);
            if (sameDiscipline != null) {
                candidates.add(new Candidate(candidate, 1, "Ведёт эту же дисциплину «" + discipline + "»",
                        safeRemainingHours(sameDiscipline) <= 0, dayLoad, remaining));
                continue;
            }
            TeacherLoad related = own.stream()
                    .filter(l -> l.getDiscipline() != null && relatedDisciplines(discipline, l.getDiscipline().getName()))
                    .findFirst().orElse(null);
            if (related != null || TeacherAssignmentService.matchesSpecialization(candidate, discipline)) {
                String why = related != null
                        ? "Смежная дисциплина «" + related.getDiscipline().getName() + "»"
                        : "Специализация подходит под «" + discipline + "»";
                candidates.add(new Candidate(candidate, 2, why, true, dayLoad, remaining));
                continue;
            }
            boolean sameProfile = own.stream().anyMatch(l -> l.getGroup() != null
                    && (l.getGroup().getId().equals(groupId)
                    || (specialty != null && !specialty.isBlank() && specialty.equalsIgnoreCase(l.getGroup().getSpecialty()))));
            boolean sameDepartment = sickTeacher.getDepartment() != null && !sickTeacher.getDepartment().isBlank()
                    && sickTeacher.getDepartment().equalsIgnoreCase(candidate.getDepartment());
            if (sameProfile || sameDepartment) {
                candidates.add(new Candidate(candidate, 3, sameProfile
                        ? "Преподаёт у этой группы или специальности, свободен в это время"
                        : "Та же кафедра, свободен в это время", true, dayLoad, remaining));
                continue;
            }
            candidates.add(new Candidate(candidate, 4, "Свободен в это время", true, dayLoad, remaining));
        }

        return candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::priorityRank)
                        .thenComparingInt(Candidate::dayLoad)
                        .thenComparing(Comparator.comparingInt(Candidate::remainingHours).reversed()))
                .findFirst()
                .orElse(null);
    }

    static boolean relatedDisciplines(String a, String b) {
        if (a == null || b == null) return false;
        String ma = moduleCode(a);
        String mb = moduleCode(b);
        if (ma != null && ma.equals(mb)) return true;
        return com.karyakina.schedule.util.StringSimilarity.similarity(a, b) >= 0.75;
    }

    private static final java.util.regex.Pattern MODULE = java.util.regex.Pattern.compile(
            "(мдк|пм|оп|оуд|ен|огсэ)\\s*\\.?\\s*0*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    private static String moduleCode(String name) {
        java.util.regex.Matcher m = MODULE.matcher(name.toLowerCase(java.util.Locale.ROOT));
        return m.find() ? m.group(1) + m.group(2) : null;
    }

    private boolean isFree(Teacher candidate, LessonInstance instance, LocalDate date, int pairIdx,
                           CalendarSlotFinder.Calendar calendar) {
        if (calendar.isTeacherAbsent(candidate.getId(), date)) return false;
        if (pairIdx >= 0) return !calendar.isTeacherBusy(candidate.getId(), date, pairIdx);
        for (LessonInstance other : lessonInstanceRepository.findActiveByActualTeacherIdAndDate(candidate.getId(), date)) {
            if (other.getSchedule() != null && overlaps(other.getSchedule(), instance.getSchedule())) return false;
        }
        return true;
    }

    private void occupyForSubstitute(CalendarSlotFinder.Calendar calendar, LessonInstance instance, Teacher substitute) {
        int pairIdx = GenerationGrid.pairIndex(instance.getSchedule().getStartTime());
        if (pairIdx < 0) return;
        LocalDate date = instance.getLessonDate();
        calendar.release(instance.getSchedule().getId(), date);
        calendar.occupy(new CalendarSlotFinder.Slot(date, pairIdx, instance.getSchedule().getClassroom(),
                        AcademicYearUtil.academicWeek(date, instance.getAcademicYear()), false),
                instance.getSchedule().getTeacherLoad(), substitute.getId(), instance.getSchedule().getId());
    }

    private void applySubstitution(LessonInstance instance, SickLeave sickLeave, Candidate substitute,
                                   List<RescheduleResultDTO.Substitution> substitutions) {
        SubstitutionRequest request = substitutionRequestRepository.save(SubstitutionRequest.builder()
                .lessonInstance(instance)
                .sickLeave(sickLeave)
                .originalTeacher(instance.getOriginalTeacher())
                .candidateTeacher(substitute.teacher())
                .priorityRank(substitute.priorityRank())
                .priorityReason(substitute.reason())
                .overload(substitute.overload())
                .status(SubstitutionRequest.Status.ACCEPTED)
                .respondedAt(LocalDateTime.now())
                .build());

        lessonInstanceService.replaceInstance(instance.getId(), substitute.teacher(),
                "Автозамена: " + reasonLabel(sickLeave) + " с " + sickLeave.getStartDate(),
                "system:auto-substitution");

        TeacherLoad load = instance.getSchedule().getTeacherLoad();
        String groupName = load != null && load.getGroup() != null ? load.getGroup().getName() : "—";
        String disciplineName = load != null && load.getDiscipline() != null ? load.getDiscipline().getName() : "—";

        String message = "Замена " + instance.getLessonDate() + " "
                + instance.getSchedule().getStartTime() + "–" + instance.getSchedule().getEndTime()
                + ": вместо " + instance.getOriginalTeacher().getFullName()
                + " проведёт " + substitute.teacher().getFullName()
                + ". Дисциплина: " + disciplineName + ", группа " + groupName
                + ". Причина выбора: " + substitute.reason() + (substitute.overload() ? " (переработка)." : ".");

        notifyBoth(instance.getOriginalTeacher(), substitute.teacher(), Notification.Type.SUBSTITUTION_ACCEPTED,
                "Замена на " + instance.getLessonDate(), message, request.getId());

        substitutions.add(new RescheduleResultDTO.Substitution(instance.getId(), groupName, disciplineName,
                instance.getLessonDate(), instance.getSchedule().getStartTime(),
                instance.getOriginalTeacher().getFullName(), substitute.teacher().getId(),
                substitute.teacher().getFullName(), substitute.reason(), substitute.overload()));
    }

    private RescheduleResultDTO.Moved tryMoveToFreeSlot(LessonInstance instance, SickLeave sickLeave,
                                                        CalendarSlotFinder.Calendar calendar,
                                                        String[] failure) {
        Schedule original = instance.getSchedule();
        TeacherLoad load = original.getTeacherLoad();
        Teacher teacher = instance.getOriginalTeacher();
        Integer academicYear = instance.getAcademicYear();
        if (load == null || load.getGroup() == null || teacher == null) {
            return null;
        }
        int semester = AcademicYearUtil.semesterOfDate(instance.getLessonDate());
        if (semester == 0) {
            return null;
        }
        LocalDate from = sickLeave.getEndDate().plusDays(1);
        LocalDate until = AcademicYearUtil.semesterEnd(semester, academicYear);
        CalendarSlotFinder.Outcome outcome = calendar.find(new CalendarSlotFinder.Request(load, teacher.getId(),
                from, until, semester, original.getClassroom(), GenerationGrid.pairIndex(original.getStartTime()), true));
        if (!outcome.found()) {
            failure[0] = outcome.diagnostics().describe(CalendarSlotFinder.weekCap(load.getGroup()));
            return null;
        }
        CalendarSlotFinder.Slot slot = outcome.slot();
        LocalDate date = slot.date();
        LocalTime start = GenerationGrid.start(slot.pairIdx());
        if (slot.createsWindow()) {
            failure[0] = "Внимание: " + (load.getDiscipline() != null ? load.getDiscipline().getName() : "пара")
                    + " (группа " + load.getGroup().getName() + ") перенесена на " + date + ", "
                    + (slot.pairIdx() + 1) + "-я пара — у группы в этот день будет окно, без окна места не нашлось.";
        }
        LocalTime end = GenerationGrid.end(slot.pairIdx());
        String room = slot.room();

        Schedule makeup = scheduleRepository.save(Schedule.builder()
                .teacherLoad(load)
                .dayOfWeek(date.getDayOfWeek())
                .startTime(start)
                .endTime(end)
                .classroom(room)
                .academicWeek(slot.week())
                .academicYear(academicYear)
                .semester(semester)
                .rescheduledFromDate(instance.getLessonDate())
                .rescheduledReason("Отсутствие преподавателя (" + reasonLabel(sickLeave) + ")")
                .sickLeaveId(sickLeave.getId())
                .build());
        calendar.occupy(slot, load, teacher.getId(), makeup.getId());
        LessonInstance cancelledInstance = lessonInstanceService.cancelInstance(instance.getId(),
                "Перенесено на " + date + " " + start + " в связи с отсутствием преподавателя ("
                        + reasonLabel(sickLeave) + ")",
                "system:auto-reschedule");
        cancelledInstance.setCancelledBySickLeaveId(sickLeave.getId());
        lessonInstanceRepository.save(cancelledInstance);

        String groupName = load.getGroup().getName();
        String disciplineName = load.getDiscipline() == null ? "—" : load.getDiscipline().getName();
        String message = "Пара перенесена: " + disciplineName + ", группа " + groupName
                + ". Было: " + instance.getLessonDate() + " " + original.getStartTime()
                + ". Стало: " + date + " " + start + ", ауд. " + room
                + ". Преподаватель не меняется: " + teacher.getFullName() + "."
                + (slot.createsWindow() ? " Без окна у группы поставить не удалось." : "");

        notificationService.notifyTeacher(teacher, Notification.Type.SCHEDULE_CHANGED,
                "Ваша пара перенесена на " + date, message, null);
        notificationService.notifyAdmins(Notification.Type.SCHEDULE_CHANGED,
                "Пара перенесена автоматически: " + date, message, null);
        log.info("Пара {} перенесена на {} {} (ауд. {}), новая запись расписания {}",
                instance.getId(), date, start, room, makeup.getId());

        return new RescheduleResultDTO.Moved(instance.getId(), groupName, disciplineName,
                instance.getLessonDate(), original.getStartTime(), date, start, room);
    }

    private RescheduleResultDTO.Unresolved describeUnresolved(LessonInstance instance, String moveFailure) {
        TeacherLoad load = instance.getSchedule() == null ? null : instance.getSchedule().getTeacherLoad();
        return new RescheduleResultDTO.Unresolved(
                instance.getId(),
                load != null && load.getGroup() != null ? load.getGroup().getName() : "—",
                load != null && load.getDiscipline() != null ? load.getDiscipline().getName() : "—",
                instance.getLessonDate(),
                instance.getSchedule() == null ? null : instance.getSchedule().getStartTime(),
                "нет свободного преподавателя этой дисциплины и нет свободного слота для переноса до конца семестра"
                        + (moveFailure != null ? " (" + moveFailure + ")" : ""));
    }

    private MissingResourceRequest unresolvedIssue(LessonInstance instance, Teacher sickTeacher) {
        TeacherLoad load = instance.getSchedule() == null ? null : instance.getSchedule().getTeacherLoad();
        String groupName = load != null && load.getGroup() != null ? load.getGroup().getName() : "—";
        String disciplineName = load != null && load.getDiscipline() != null ? load.getDiscipline().getName() : "—";

        List<MissingResourceRequest.Choice> teacherChoices = teacherRepository.findAll().stream()
                .filter(t -> !t.getId().equals(sickTeacher.getId()))
                .limit(30)
                .map(t -> new MissingResourceRequest.Choice(String.valueOf(t.getId()), t.getFullName()))
                .toList();

        return MissingResourceRequest.builder(MissingResourceRequest.Code.SUBSTITUTE_NOT_FOUND,
                        "Не закрыта пара " + instance.getLessonDate() + ": " + disciplineName + ", " + groupName)
                .severity(MissingResourceRequest.Severity.BLOCKING)
                .message("Замену найти не удалось, свободного слота для переноса до конца семестра тоже нет. "
                        + "Как поступить с этой парой?")
                .context("lessonInstanceId", instance.getId())
                .context("groupName", groupName)
                .context("disciplineName", disciplineName)
                .option(MissingResourceRequest.ResolutionOption.of("CANCEL_LESSON", "Отменить занятие",
                        Map.of("lessonInstanceId", instance.getId())))
                .option(MissingResourceRequest.ResolutionOption.of("INDEPENDENT_WORK",
                        "Назначить самостоятельную работу", Map.of("lessonInstanceId", instance.getId())))
                .option(MissingResourceRequest.ResolutionOption.withInput("ASSIGN_TEACHER_MANUALLY",
                        "Назначить преподавателя вручную",
                        MissingResourceRequest.InputSpec.select("Преподаватель", "teacherId", teacherChoices)))
                .build();
    }

    private SickLeave findOrCreateSickLeave(Teacher teacher, LocalDate startDate, LocalDate endDate) {
        List<SickLeave> existing = sickLeaveRepository.findByTeacherIdAndDateRange(teacher.getId(), startDate);
        for (SickLeave leave : existing) {
            if (!leave.getStartDate().isAfter(startDate) && !leave.getEndDate().isBefore(endDate)) {
                return leave;
            }
        }
        return sickLeaveRepository.save(SickLeave.builder()
                .teacher(teacher)
                .startDate(startDate)
                .endDate(endDate)
                .reason("больничный")
                .academicYear(AcademicYearUtil.academicYearOf(startDate))
                .build());
    }

    private List<LessonInstance> collectAffectedLessons(Teacher teacher, SickLeave sickLeave,
                                                        LocalDate startDate, LocalDate endDate) {
        Map<Long, LessonInstance> affected = new LinkedHashMap<>();
        Integer academicYear = sickLeave.getAcademicYear();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            try {
                for (LessonInstance instance : lessonInstanceService.generateInstancesForDate(date, academicYear)) {
                    if (instance.getSchedule() == null) continue;
                    boolean wasSubstitute = false;
                    if (instance.getStatus() == LessonInstance.Status.REPLACED
                            && instance.getActualTeacher() != null
                            && instance.getActualTeacher().getId().equals(teacher.getId())
                            && !LessonInstanceService.isCredited(instance)) {
                        instance = lessonInstanceService.revertReplacement(instance.getId(),
                                "Заменяющий тоже отсутствует");
                        wasSubstitute = true;
                    }
                    if (instance.getStatus() == LessonInstance.Status.PLANNED
                            && instance.getOriginalTeacher() != null
                            && (wasSubstitute || instance.getOriginalTeacher().getId().equals(teacher.getId()))) {
                        affected.put(instance.getId(), instance);
                    }
                }
            } catch (Exception e) {
                log.warn("Не удалось материализовать занятия на {}: {}", date, e.getMessage());
            }
        }
        return new ArrayList<>(affected.values());
    }

    private void notifySummary(Teacher teacher, SickLeave sickLeave, int affected, int substituted,
                               int moved, int unresolved) {
        try {
            notificationService.notifyAdmins(Notification.Type.INFO,
                    "Отсутствие преподавателя: " + teacher.getFullName(),
                    "Период: " + sickLeave.getStartDate() + " — " + sickLeave.getEndDate()
                            + ". Затронуто пар: " + affected + ". Закрыто заменой: " + substituted
                            + ", перенесено: " + moved + ", требует решения: " + unresolved + ".",
                    null);
        } catch (Exception e) {
            log.warn("Не удалось отправить сводку по больничному: {}", e.getMessage());
        }
    }

    private void notifyBoth(Teacher original, Teacher substitute, Notification.Type type,
                            String title, String message, Long requestId) {
        try {
            notificationService.notifyTeacher(original, type, title, message, requestId);
            notificationService.notifyTeacher(substitute, type, title, message, requestId);
            notificationService.notifyAdmins(type, title, message, requestId);
        } catch (Exception e) {
            log.warn("Не удалось отправить уведомление о замене: {}", e.getMessage());
        }
    }

    private int safeRemainingHours(TeacherLoad load) {
        try {
            return load.getRemainingHours();
        } catch (Exception e) {
            return 0;
        }
    }

    private String reasonLabel(SickLeave sickLeave) {
        return sickLeave.getReason() == null || sickLeave.getReason().isBlank()
                ? "больничный" : sickLeave.getReason();
    }

    private boolean overlaps(Schedule a, Schedule b) {
        if (a == null || b == null) {
            return false;
        }
        return overlaps(a.getStartTime(), a.getEndTime(), b.getStartTime(), b.getEndTime());
    }

    private boolean overlaps(LocalTime startA, LocalTime endA, LocalTime startB, LocalTime endB) {
        if (startA == null || endA == null || startB == null || endB == null) {
            return false;
        }
        return startA.isBefore(endB) && startB.isBefore(endA);
    }
}
