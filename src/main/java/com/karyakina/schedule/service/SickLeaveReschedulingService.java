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

/**
 * СЦЕНАРИЙ 2: АВТОМАТИЧЕСКАЯ РЕАКЦИЯ НА БОЛЬНИЧНЫЙ.
 *
 * <p>Точка входа — {@link #handleTeacherSickLeave(Long, LocalDate, LocalDate)}. Для каждой
 * затронутой пары по очереди пробуются два пути, и только если оба не сработали, вопрос
 * уходит администратору:
 * <ol>
 *   <li><b>Замена.</b> Среди преподавателей, ведущих эту же дисциплину (по {@code TeacherLoad}),
 *       ищем свободного в этот слот: не болеет, не занят другой парой, вписывается в дневной
 *       лимит. Побеждает тот, у кого больше остаток плановых часов — замена без переработки.
 *       Меняется только преподаватель, расписание группы остаётся прежним.</li>
 *   <li><b>Перенос.</b> Если замены нет — ищем «окно» группы: любой день после больничного и
 *       любую пару в сетке, где свободны группа, исходный преподаватель и хотя бы одна
 *       аудитория. Создаётся одноразовая запись расписания на конкретную неделю, исходное
 *       занятие отменяется с пометкой о переносе.</li>
 *   <li><b>Вопрос администратору.</b> Три варианта, каждый — существующий эндпоинт
 *       {@code LessonInstanceAdminController}: отменить пару, назначить самостоятельную работу,
 *       назначить преподавателя вручную.</li>
 * </ol>
 *
 * <p>Отличие от прежнего поведения: результат возвращается структурой {@link RescheduleResultDTO}
 * (её показывает интерфейс), перенос ищется по всем парам дня, а не только в тот же слот,
 * и аудитория подбирается заново, если прежняя занята.
 */
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

    /** Горизонт поиска свободного слота для переноса (календарных дней после окончания больничного). */
    private static final int RESCHEDULE_HORIZON_DAYS = 21;
    private static final int DEFAULT_TEACHER_MAX_PAIRS_PER_DAY = 4;

    /**
     * Обрабатывает отсутствие преподавателя за период: ищет замены, переносит пары,
     * а неразрешимое отдаёт администратору с вариантами действий. Исключений не бросает.
     *
     * @param teacherId преподаватель, который заболел
     * @param startDate первый день отсутствия (включительно)
     * @param endDate   последний день отсутствия (включительно)
     */
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
                    RescheduleResultDTO.Moved move = tryMoveToFreeSlot(instance, sickLeave);
                    if (move != null) {
                        moved.add(move);
                        continue;
                    }
                    unresolved.add(describeUnresolved(instance));
                    missing.add(unresolvedIssue(instance, teacher));
                } catch (Exception perLesson) {
                    // Одна проблемная пара не должна ронять обработку всего больничного.
                    log.error("Ошибка обработки пары {} при больничном преподавателя {}",
                            instance.getId(), teacherId, perLesson);
                    warnings.add("Пара " + instance.getLessonDate() + ": обработать автоматически не удалось ("
                            + perLesson.getClass().getSimpleName() + "), требуется ручная проверка.");
                    unresolved.add(describeUnresolved(instance));
                }
            }

            notifySummary(teacher, sickLeave, affected.size(), substitutions.size(), moved.size(), unresolved.size());

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

    // ------------------------------------------------------------------ поиск замены

    private record Candidate(Teacher teacher, int priorityRank, String reason, boolean overload, int remainingHours) {
    }

    /**
     * Кандидаты на замену: сначала те, кто уже ведёт эту дисциплину, затем коллеги по кафедре.
     * Проверяется реальная свободность в слоте: не болеет, не занят своей парой, есть запас
     * по числу пар в день.
     */
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

        // Лучший — с наибольшим остатком плановых часов: замена без переработки предпочтительнее.
        return candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::priorityRank)
                        .thenComparing(Comparator.comparingInt(Candidate::remainingHours).reversed()))
                .findFirst()
                .orElse(null);
    }

    /** Свободен ли преподаватель в это время: не болеет, не занят парой, есть запас пар в день. */
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

        // Шаблон расписания тоже занимает время, даже если занятие ещё не материализовано.
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

    // ------------------------------------------------------------------ перенос пары

    /**
     * Ищет «окно» группы после больничного: перебираются все дни горизонта и все пары сетки.
     * Свободными должны быть группа, исходный преподаватель и хотя бы одна аудитория.
     * Предпочтение — ближайшая дата и то же время дня, что у исходной пары.
     */
    private RescheduleResultDTO.Moved tryMoveToFreeSlot(LessonInstance instance, SickLeave sickLeave) {
        Schedule original = instance.getSchedule();
        TeacherLoad load = original.getTeacherLoad();
        Teacher teacher = instance.getOriginalTeacher();
        Integer academicYear = instance.getAcademicYear();
        if (load == null || load.getGroup() == null) {
            return null;
        }
        int originalPairIdx = GenerationGrid.pairIndex(original.getStartTime());

        LocalDate from = sickLeave.getEndDate().plusDays(1);
        LocalDate until = from.plusDays(RESCHEDULE_HORIZON_DAYS);

        for (LocalDate date = from; !date.isAfter(until); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY || GenerationGrid.dayIndex(date.getDayOfWeek()) < 0) {
                continue;
            }
            List<LessonInstance> dayInstances = lessonInstanceService.generateInstancesForDate(date, academicYear);
            List<LessonInstance> active = dayInstances.stream()
                    .filter(li -> li.getStatus() != LessonInstance.Status.CANCELLED)
                    .filter(li -> li.getId() == null || !li.getId().equals(instance.getId()))
                    .filter(li -> li.getSchedule() != null)
                    .toList();

            for (int pairIdx : pairSearchOrder(originalPairIdx)) {
                LocalTime start = GenerationGrid.start(pairIdx);
                LocalTime end = GenerationGrid.end(pairIdx);

                if (isGroupBusy(active, load.getGroup().getId(), start, end)) {
                    continue;
                }
                if (isTeacherBusy(active, teacher.getId(), start, end)) {
                    continue;
                }
                String room = pickFreeRoom(active, load, original.getClassroom(), start, end);
                if (room == null) {
                    continue;
                }

                Schedule makeup = scheduleRepository.save(Schedule.builder()
                        .teacherLoad(load)
                        .dayOfWeek(date.getDayOfWeek())
                        .startTime(start)
                        .endTime(end)
                        .classroom(room)
                        .academicWeek(academicWeekOf(date, academicYear)) // только эта неделя, не «каждую»
                        .academicYear(academicYear)
                        .build());
                lessonInstanceService.generateInstancesForDate(date, academicYear);
                lessonInstanceService.cancelInstance(instance.getId(),
                        "Перенесено на " + date + " " + start + " в связи с отсутствием преподавателя ("
                                + reasonLabel(sickLeave) + ")",
                        "system:auto-reschedule");

                String groupName = load.getGroup().getName();
                String disciplineName = load.getDiscipline() == null ? "—" : load.getDiscipline().getName();
                String message = "Пара перенесена: " + disciplineName + ", группа " + groupName
                        + ". Было: " + instance.getLessonDate() + " " + original.getStartTime()
                        + ". Стало: " + date + " " + start + ", ауд. " + room
                        + ". Преподаватель не меняется: " + teacher.getFullName() + ".";

                notificationService.notifyTeacher(teacher, Notification.Type.SCHEDULE_CHANGED,
                        "Ваша пара перенесена на " + date, message, null);
                notificationService.notifyAdmins(Notification.Type.SCHEDULE_CHANGED,
                        "Пара перенесена автоматически: " + date, message, null);
                log.info("Пара {} перенесена на {} {} (ауд. {}), новая запись расписания {}",
                        instance.getId(), date, start, room, makeup.getId());

                return new RescheduleResultDTO.Moved(instance.getId(), groupName, disciplineName,
                        instance.getLessonDate(), original.getStartTime(), date, start, room);
            }
        }
        return null;
    }

    /** Сначала пробуем то же время дня, что и у исходной пары, потом остальные по порядку. */
    private List<Integer> pairSearchOrder(int preferredPairIdx) {
        List<Integer> order = new ArrayList<>();
        if (preferredPairIdx >= 0 && preferredPairIdx < GenerationGrid.pairsPerDay()) {
            order.add(preferredPairIdx);
        }
        for (int i = 0; i < GenerationGrid.pairsPerDay(); i++) {
            if (!order.contains(i)) {
                order.add(i);
            }
        }
        return order;
    }

    private boolean isGroupBusy(List<LessonInstance> active, Long groupId, LocalTime start, LocalTime end) {
        return active.stream().anyMatch(li -> {
            TeacherLoad other = li.getSchedule().getTeacherLoad();
            return other != null && other.getGroup() != null && other.getGroup().getId().equals(groupId)
                    && overlaps(li.getSchedule().getStartTime(), li.getSchedule().getEndTime(), start, end);
        });
    }

    private boolean isTeacherBusy(List<LessonInstance> active, Long teacherId, LocalTime start, LocalTime end) {
        return active.stream().anyMatch(li -> li.getActualTeacher() != null
                && li.getActualTeacher().getId().equals(teacherId)
                && overlaps(li.getSchedule().getStartTime(), li.getSchedule().getEndTime(), start, end));
    }

    /**
     * Свободная аудитория в этом слоте: сначала прежняя, потом минимальная достаточная
     * по вместимости группы. Прежняя версия просто пропускала день, если исходная занята.
     */
    private String pickFreeRoom(List<LessonInstance> active, TeacherLoad load, String preferredRoom,
                                LocalTime start, LocalTime end) {
        Set<String> busy = new LinkedHashSet<>();
        for (LessonInstance li : active) {
            String room = li.getSchedule().getClassroom();
            if (room != null && overlaps(li.getSchedule().getStartTime(), li.getSchedule().getEndTime(), start, end)) {
                busy.add(room);
            }
        }
        if (preferredRoom != null && !busy.contains(preferredRoom)) {
            return preferredRoom;
        }
        int students = load.getGroup() != null && load.getGroup().getStudentCount() != null
                ? load.getGroup().getStudentCount() : 0;
        String disciplineName = load.getDiscipline() != null ? load.getDiscipline().getName() : null;
        return GenerationGrid.rooms(classroomRepository.findAll()).stream()
                .filter(room -> !busy.contains(room.name()))
                .filter(room -> room.capacity() <= 0 || students <= 0 || room.capacity() >= students)
                .filter(room -> room.isOpenFor(disciplineName))
                .min(Comparator.comparingInt(GenerationGrid.Room::capacity))
                .map(GenerationGrid.Room::name)
                .orElse(null);
    }

    // ------------------------------------------------------------------ неразрешённое

    private RescheduleResultDTO.Unresolved describeUnresolved(LessonInstance instance) {
        TeacherLoad load = instance.getSchedule() == null ? null : instance.getSchedule().getTeacherLoad();
        return new RescheduleResultDTO.Unresolved(
                instance.getId(),
                load != null && load.getGroup() != null ? load.getGroup().getName() : "—",
                load != null && load.getDiscipline() != null ? load.getDiscipline().getName() : "—",
                instance.getLessonDate(),
                instance.getSchedule() == null ? null : instance.getSchedule().getStartTime(),
                "нет свободного преподавателя этой дисциплины и нет свободного слота для переноса в ближайшие "
                        + RESCHEDULE_HORIZON_DAYS + " дней");
    }

    /** Три варианта действий администратора — каждый уже реализован эндпоинтом. */
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
                .message("Замену найти не удалось, свободного слота для переноса в ближайшие "
                        + RESCHEDULE_HORIZON_DAYS + " дней тоже нет. Как поступить с этой парой?")
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

    // ------------------------------------------------------------------ вспомогательное

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
                .academicYear(com.karyakina.schedule.util.AcademicYearUtil.getCurrentAcademicYearStart())
                .build());
    }

    /** Материализует занятия за период и оставляет только запланированные пары заболевшего. */
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
