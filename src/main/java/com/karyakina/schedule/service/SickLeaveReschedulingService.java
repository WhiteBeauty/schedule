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
    private final HoursRecalculationService hoursRecalculationService;
    private final SettingsService settingsService;

    private static final int DEFAULT_TEACHER_MAX_PAIRS_PER_DAY = 4;

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

            CalendarSlotFinder.Calendar calendar = null;
            Map<Long, Set<LocalDate>> holes = new LinkedHashMap<>();
            for (LessonInstance instance : affected) {
                try {
                    Candidate substitute = findBestSubstitute(instance, teacher);
                    if (substitute != null) {
                        applySubstitution(instance, sickLeave, substitute, substitutions);
                        if (substitute.overload()) {
                            warnings.add("Замену на " + instance.getLessonDate() + " ведёт "
                                    + substitute.teacher().getFullName()
                                    + " сверх плановой нагрузки — часы уйдут в переработку.");
                        }
                        continue;
                    }
                    if (calendar == null) {
                        calendar = slotFinder.open(instance.getAcademicYear(), endDate.plusDays(1),
                                AcademicYearUtil.semesterEnd(2, instance.getAcademicYear()));
                    }
                    String[] failure = new String[1];
                    RescheduleResultDTO.Moved move = tryMoveToFreeSlot(instance, sickLeave, calendar, failure);
                    if (move != null) {
                        moved.add(move);
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

            int shifted = closeWindows(holes, sickLeave.getAcademicYear());
            if (shifted > 0) {
                warnings.add("Чтобы у групп не осталось окон в дни отсутствия, сдвинуто пар внутри дня: " + shifted + ".");
            }

            notifySummary(teacher, sickLeave, affected.size(), substitutions.size(), moved.size(), unresolved.size());

            // Пары, которые не удалось ни заменить, ни перенести, восполняются динамическим пересчётом часов
            // (если он включён): недостающие часы ставятся в свободные слоты до конца семестра.
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

    private record Candidate(Teacher teacher, int priorityRank, String reason, boolean overload, int remainingHours) {
    }

    private Candidate findBestSubstitute(LessonInstance instance, Teacher sickTeacher) {
        TeacherLoad originalLoad = instance.getSchedule().getTeacherLoad();
        if (originalLoad == null || originalLoad.getDiscipline() == null) {
            return null;
        }
        LocalDate date = instance.getLessonDate();
        Integer academicYear = instance.getAcademicYear();

        Set<Long> seen = new LinkedHashSet<>();
        seen.add(sickTeacher.getId());
        List<Candidate> candidates = new ArrayList<>();

        for (TeacherLoad load : loadRepository.findByDisciplineIdAndAcademicYear(
                originalLoad.getDiscipline().getId(), academicYear)) {
            Teacher candidate = load.getTeacher();
            if (candidate == null || !seen.add(candidate.getId())) {
                continue;
            }
            if (!isAvailable(candidate, instance, date)) {
                continue;
            }
            int remaining = safeRemainingHours(load);
            candidates.add(new Candidate(candidate, 1,
                    "Ведёт дисциплину «" + originalLoad.getDiscipline().getName() + "» у других групп",
                    remaining <= 0, remaining));
        }

        if (candidates.isEmpty() && sickTeacher.getDepartment() != null && !sickTeacher.getDepartment().isBlank()) {
            for (Teacher candidate : teacherRepository.findByDepartment(sickTeacher.getDepartment())) {
                if (!seen.add(candidate.getId()) || !isAvailable(candidate, instance, date)) {
                    continue;
                }
                candidates.add(new Candidate(candidate, 2,
                        "Та же кафедра (" + sickTeacher.getDepartment() + "), свободен в это время", true, 0));
            }
        }

        return candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::priorityRank)
                        .thenComparing(Comparator.comparingInt(Candidate::remainingHours).reversed()))
                .findFirst()
                .orElse(null);
    }

    private boolean isAvailable(Teacher candidate, LessonInstance instance, LocalDate date) {
        try {
            if (lessonInstanceService.isTeacherSickOnDate(candidate.getId(), date)) {
                return false;
            }
        } catch (Exception e) {
            log.debug("Проверка больничного кандидата {} не удалась: {}", candidate.getId(), e.getMessage());
        }

        List<LessonInstance> busyToday = lessonInstanceRepository
                .findActiveByActualTeacherIdAndDate(candidate.getId(), date);
        for (LessonInstance other : busyToday) {
            if (other.getSchedule() == null) {
                continue;
            }
            if (overlaps(other.getSchedule(), instance.getSchedule())) {
                return false;
            }
        }
        int maxPerDay = candidate.getMaxPairsPerDay() != null && candidate.getMaxPairsPerDay() > 0
                ? candidate.getMaxPairsPerDay() : DEFAULT_TEACHER_MAX_PAIRS_PER_DAY;
        if (busyToday.size() >= maxPerDay) {
            return false;
        }

        for (Schedule schedule : scheduleRepository.findByTeacherLoadTeacherIdAndAcademicYear(
                candidate.getId(), instance.getAcademicYear())) {
            if (schedule.getDayOfWeek() != date.getDayOfWeek()) {
                continue;
            }
            if (schedule.getAcademicWeek() != null
                    && !schedule.getAcademicWeek().equals(academicWeekOf(date, instance.getAcademicYear()))) {
                continue;
            }
            if (overlaps(schedule, instance.getSchedule())) {
                return false;
            }
        }
        return true;
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
                .build());
        calendar.occupy(slot, load, teacher.getId(), makeup.getId());
        lessonInstanceService.cancelInstance(instance.getId(),
                "Перенесено на " + date + " " + start + " в связи с отсутствием преподавателя ("
                        + reasonLabel(sickLeave) + ")",
                "system:auto-reschedule");

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


    /**
     * После переноса пары заболевшего преподавателя в дне группы остаётся дыра. Закрываем её: последняя пара
     * группы в этот день сдвигается в окно (разовой записью), если её преподаватель и аудитория свободны.
     */
    private int closeWindows(Map<Long, Set<LocalDate>> holes, Integer academicYear) {
        if (holes.isEmpty()) return 0;
        LocalDate today = LocalDate.now();
        LocalDate min = null;
        LocalDate max = null;
        for (Set<LocalDate> dates : holes.values()) {
            for (LocalDate d : dates) {
                if (min == null || d.isBefore(min)) min = d;
                if (max == null || d.isAfter(max)) max = d;
            }
        }
        CalendarSlotFinder.Calendar cal = slotFinder.open(academicYear, min, max);
        int shifted = 0;
        for (Map.Entry<Long, Set<LocalDate>> entry : holes.entrySet()) {
            Long groupId = entry.getKey();
            for (LocalDate date : entry.getValue()) {
                if (date.isBefore(today)) continue;
                for (int guard = 0; guard < GenerationGrid.pairsPerDay(); guard++) {
                    java.util.TreeSet<Integer> pairs = cal.pairsOfGroup(groupId, date);
                    if (pairs.size() < 2) break;
                    int gap = -1;
                    for (int p = pairs.first() + 1; p < pairs.last(); p++) {
                        if (!pairs.contains(p)) { gap = p; break; }
                    }
                    if (gap < 0) break;
                    // Кандидаты: последняя пара дня, пара сразу после окна (окно сдвигается и закрывается на следующей
                    // итерации), пара перед окном и первая пара (окно превращается в более поздний старт дня).
                    Integer chosenIdx = null;
                    CalendarSlotFinder.Occ lastOcc = null;
                    Schedule source = null;
                    String room = null;
                    LessonInstance li = null;
                    for (int candidateIdx : new java.util.LinkedHashSet<>(List.of(pairs.last(), pairs.ceiling(gap), pairs.floor(gap), pairs.first()))) {
                        final int idx = candidateIdx;
                        CalendarSlotFinder.Occ occ = cal.groupOccurrences(groupId, date).stream()
                                .filter(o -> o.pairIdx == idx && o.scheduleId != null).findFirst().orElse(null);
                        if (occ == null) continue;
                        Schedule src = scheduleRepository.findById(occ.scheduleId).orElse(null);
                        if (src == null || src.getTeacherLoad() == null) continue;
                        if (cal.isTeacherBusy(occ.teacherId, date, gap) || cal.isTeacherAbsent(occ.teacherId, date)) continue;
                        TeacherLoad srcLoad = src.getTeacherLoad();
                        int students = srcLoad.getGroup().getStudentCount() == null ? 0 : srcLoad.getGroup().getStudentCount();
                        String r = cal.freeRoom(src.getClassroom(), date, gap, students,
                                srcLoad.getDiscipline() != null ? srcLoad.getDiscipline().getName() : null);
                        if (r == null) continue;
                        lessonInstanceService.generateInstancesForDate(date, academicYear);
                        LessonInstance inst = lessonInstanceRepository.findByScheduleIdAndLessonDate(src.getId(), date).orElse(null);
                        if (inst == null || inst.getStatus() != LessonInstance.Status.PLANNED) continue;
                        chosenIdx = idx;
                        lastOcc = occ;
                        source = src;
                        room = r;
                        li = inst;
                        break;
                    }
                    if (chosenIdx == null) {
                        log.info("Окно у группы {} {} (пара {}) закрыть сдвигом не удалось", groupId, date, gap + 1);
                        break;
                    }
                    final int lastIdx = chosenIdx;
                    TeacherLoad load = source.getTeacherLoad();

                    String note = "Сдвинута на " + (gap + 1) + "-ю пару, чтобы у группы не было окна";
                    lessonInstanceService.cancelInstance(li.getId(), note, "system:auto-reschedule");
                    int week = AcademicYearUtil.academicWeek(date, academicYear);
                    Schedule row = scheduleRepository.save(Schedule.builder()
                            .teacherLoad(load)
                            .dayOfWeek(date.getDayOfWeek())
                            .startTime(GenerationGrid.start(gap))
                            .endTime(GenerationGrid.end(gap))
                            .classroom(room)
                            .academicWeek(week)
                            .academicYear(academicYear)
                            .semester(AcademicYearUtil.semesterOfDate(date))
                            .rescheduledFromDate(date)
                            .rescheduledReason("Сдвиг внутри дня: закрытие окна после переноса")
                            .build());
                    cal.release(source.getId(), date);
                    cal.occupy(new CalendarSlotFinder.Slot(date, gap, room, week, false), load, lastOcc.teacherId, row.getId());
                    lessonInstanceService.generateInstancesForDate(date, academicYear);
                    shifted++;
                    if (load.getTeacher() != null) {
                        try {
                            notificationService.notifyTeacher(load.getTeacher(), Notification.Type.SCHEDULE_CHANGED,
                                    "Пара " + date + " сдвинута",
                                    (load.getDiscipline() != null ? load.getDiscipline().getName() : "Пара") + ", группа "
                                            + load.getGroup().getName() + ": " + date + " вместо " + (lastIdx + 1)
                                            + "-й пары будет " + (gap + 1) + "-я (" + GenerationGrid.start(gap)
                                            + "), ауд. " + room + ".", null);
                        } catch (Exception e) {
                            log.warn("Не удалось уведомить о сдвиге пары: {}", e.getMessage());
                        }
                    }
                }
            }
        }
        return shifted;
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
                    if (instance.getStatus() == LessonInstance.Status.PLANNED
                            && instance.getOriginalTeacher() != null
                            && instance.getOriginalTeacher().getId().equals(teacher.getId())
                            && instance.getSchedule() != null) {
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

    private int academicWeekOf(LocalDate date, Integer academicYear) {
        try {
            return lessonInstanceService.computeAcademicWeek(date, academicYear);
        } catch (Exception e) {
            return 1;
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
