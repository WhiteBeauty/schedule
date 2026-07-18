package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * МОДУЛЬ ОБРАБОТКИ ФОРС-МАЖОРОВ И АВТОМАТИЧЕСКОЙ ЗАМЕНЫ.
 *
 * При регистрации {@link SickLeave} для каждого затронутого занятия сразу подбирается
 * лучший кандидат и замена применяется в расписании. Уведомления уходят:
 * заболевшему, заменяющему и администрации.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class SubstitutionService {

    private final LessonInstanceService lessonInstanceService;
    private final LessonInstanceRepository lessonInstanceRepository;
    private final SubstitutionRequestRepository substitutionRequestRepository;
    private final TeacherLoadRepository loadRepository;
    private final TeacherRepository teacherRepository;
    private final ScheduleRepository scheduleRepository;
    private final NotificationService notificationService;

    /**
     * Точка входа: больничный/форс-мажор зарегистрирован.
     * Находит занятия в диапазоне дат, назначает замену и рассылает уведомления.
     */
    @Transactional
    public void handleNewSickLeave(SickLeave sickLeave) {
        Integer academicYear = sickLeave.getAcademicYear();
        String reasonLabel = (sickLeave.getReason() == null || sickLeave.getReason().isBlank())
                ? "больничный" : sickLeave.getReason();

        // INFO — гарантированно проходит старый PostgreSQL CHECK; SICK_LEAVE тоже ок после SchemaConstraintFixer
        notificationService.notifyAdmins(
                Notification.Type.INFO,
                "Отсутствие преподавателя: " + sickLeave.getTeacher().getFullName(),
                "Период: " + sickLeave.getStartDate() + " — " + sickLeave.getEndDate()
                        + ". Причина: " + reasonLabel + ". Запущен поиск замены по расписанию.",
                null);

        List<LessonInstance> affected = new ArrayList<>();

        for (LocalDate date = sickLeave.getStartDate(); !date.isAfter(sickLeave.getEndDate()); date = date.plusDays(1)) {
            List<LessonInstance> dayInstances = lessonInstanceService.generateInstancesForDate(date, academicYear);
            for (LessonInstance instance : dayInstances) {
                if (instance.getStatus() == LessonInstance.Status.PLANNED
                        && instance.getOriginalTeacher().getId().equals(sickLeave.getTeacher().getId())) {
                    affected.add(instance);
                }
            }
        }

        log.info("Больничный преподавателя {} ({} - {}): затронуто занятий {}",
                sickLeave.getTeacher().getFullName(), sickLeave.getStartDate(), sickLeave.getEndDate(), affected.size());

        if (affected.isEmpty()) {
            notificationService.notifyAdmins(
                    Notification.Type.INFO,
                    "Нет пар для замены: " + sickLeave.getTeacher().getFullName(),
                    "На период " + sickLeave.getStartDate() + " — " + sickLeave.getEndDate()
                            + " в расписании не найдено запланированных занятий этого преподавателя.",
                    null);
            return;
        }

        for (LessonInstance instance : affected) {
            autoAssignReplacement(instance, sickLeave, Set.of());
        }
    }

    /**
     * Сразу назначает лучшего кандидата (без ожидания подтверждения), обновляет занятие
     * и уведомляет обоих преподавателей и администрацию.
     */
    @Transactional
    public void autoAssignReplacement(LessonInstance instance, SickLeave sickLeave, Set<Long> excludeTeacherIds) {
        List<Candidate> candidates = findCandidates(instance, excludeTeacherIds);

        if (candidates.isEmpty()) {
            notificationService.notifyAdmins(
                    Notification.Type.SUBSTITUTION_UNRESOLVED,
                    "Не найдена замена на " + instance.getLessonDate(),
                    "Занятие: " + instance.getSchedule().getTeacherLoad().getDiscipline().getName()
                            + ", группа " + instance.getSchedule().getTeacherLoad().getGroup().getName()
                            + ", преподаватель " + instance.getOriginalTeacher().getFullName()
                            + " отсутствует (" + sickLeave.getReason() + "), кандидаты на замену не найдены.",
                    null);
            return;
        }

        Candidate best = candidates.get(0);
        SubstitutionRequest request = SubstitutionRequest.builder()
                .lessonInstance(instance)
                .sickLeave(sickLeave)
                .originalTeacher(instance.getOriginalTeacher())
                .candidateTeacher(best.teacher)
                .priorityRank(best.priorityRank)
                .priorityReason(best.reason)
                .overload(best.overload)
                .status(SubstitutionRequest.Status.ACCEPTED)
                .respondedAt(java.time.LocalDateTime.now())
                .build();
        request = substitutionRequestRepository.save(request);

        lessonInstanceService.replaceInstance(
                instance.getId(),
                best.teacher,
                "Автозамена: " + (sickLeave.getReason() != null ? sickLeave.getReason() : "отсутствие")
                        + " с " + sickLeave.getStartDate(),
                "system:auto-substitution");

        String disc = instance.getSchedule().getTeacherLoad().getDiscipline().getName();
        String group = instance.getSchedule().getTeacherLoad().getGroup().getName();
        String when = instance.getLessonDate() + " "
                + instance.getSchedule().getStartTime() + "-" + instance.getSchedule().getEndTime();
        String msg = "Замена на " + when + ": вместо " + instance.getOriginalTeacher().getFullName()
                + " проведёт " + best.teacher.getFullName()
                + ". Дисциплина: " + disc + ", группа " + group
                + ". Причина выбора: " + best.reason
                + (best.overload ? " (переработка)" : "") + ".";

        notificationService.notifyTeacher(
                instance.getOriginalTeacher(),
                Notification.Type.SUBSTITUTION_ACCEPTED,
                "Назначена замена на " + instance.getLessonDate(),
                msg,
                request.getId());

        notificationService.notifyTeacher(
                best.teacher,
                Notification.Type.SUBSTITUTION_ACCEPTED,
                "Вы назначены на замену " + instance.getLessonDate(),
                msg,
                request.getId());

        notificationService.notifyAdmins(
                Notification.Type.SUBSTITUTION_ACCEPTED,
                "Замена выполнена: " + instance.getLessonDate(),
                msg,
                request.getId());
    }

    /**
     * Подбирает следующего кандидата (ручной/повторный сценарий после отказа).
     */
    @Transactional
    public void proposeNextCandidate(LessonInstance instance, SickLeave sickLeave, Set<Long> excludeTeacherIds) {
        List<Candidate> candidates = findCandidates(instance, excludeTeacherIds);

        if (candidates.isEmpty()) {
            notificationService.notifyAdmins(
                    Notification.Type.SUBSTITUTION_UNRESOLVED,
                    "Не найдена замена на " + instance.getLessonDate(),
                    "Занятие: " + instance.getSchedule().getTeacherLoad().getDiscipline().getName()
                            + ", группа " + instance.getSchedule().getTeacherLoad().getGroup().getName()
                            + ", преподаватель " + instance.getOriginalTeacher().getFullName()
                            + " отсутствует (" + sickLeave.getReason() + "), кандидаты на замену не найдены.",
                    null);
            return;
        }

        Candidate best = candidates.get(0);
        SubstitutionRequest request = SubstitutionRequest.builder()
                .lessonInstance(instance)
                .sickLeave(sickLeave)
                .originalTeacher(instance.getOriginalTeacher())
                .candidateTeacher(best.teacher)
                .priorityRank(best.priorityRank)
                .priorityReason(best.reason)
                .overload(best.overload)
                .status(SubstitutionRequest.Status.PENDING)
                .build();
        request = substitutionRequestRepository.save(request);

        notificationService.notifyTeacher(
                best.teacher,
                Notification.Type.SUBSTITUTION_REQUEST,
                "Просьба подтвердить замену " + instance.getLessonDate(),
                "Преподаватель " + instance.getOriginalTeacher().getFullName() + " отсутствует ("
                        + sickLeave.getReason() + "). Дисциплина: "
                        + instance.getSchedule().getTeacherLoad().getDiscipline().getName()
                        + ", группа " + instance.getSchedule().getTeacherLoad().getGroup().getName()
                        + ", " + instance.getLessonDate() + " " + instance.getSchedule().getStartTime()
                        + "-" + instance.getSchedule().getEndTime() + ". Причина выбора: " + best.reason
                        + (best.overload ? " (сверх плановой нагрузки, будет учтено как переработка)" : ""),
                request.getId());
    }

    @Transactional
    public SubstitutionRequest acceptSubstitution(Long requestId, Teacher respondingTeacher) {
        SubstitutionRequest request = substitutionRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Заявка на замену не найдена: " + requestId));

        if (!request.getCandidateTeacher().getId().equals(respondingTeacher.getId())) {
            throw new RuntimeException("Эта заявка адресована другому преподавателю");
        }
        if (request.getStatus() != SubstitutionRequest.Status.PENDING) {
            return request;
        }

        lessonInstanceService.replaceInstance(
                request.getLessonInstance().getId(),
                respondingTeacher,
                "Замена по больничному от " + request.getSickLeave().getStartDate(),
                "teacher:" + respondingTeacher.getId());

        request.setStatus(SubstitutionRequest.Status.ACCEPTED);
        request.setRespondedAt(java.time.LocalDateTime.now());
        request = substitutionRequestRepository.save(request);

        for (SubstitutionRequest other : substitutionRequestRepository
                .findByLessonInstanceIdOrderByPriorityRankAsc(request.getLessonInstance().getId())) {
            if (!other.getId().equals(request.getId()) && other.getStatus() == SubstitutionRequest.Status.PENDING) {
                other.setStatus(SubstitutionRequest.Status.EXPIRED);
                substitutionRequestRepository.save(other);
            }
        }

        String msg = respondingTeacher.getFullName() + " заменит " + request.getOriginalTeacher().getFullName()
                + " " + request.getLessonInstance().getLessonDate();

        notificationService.notifyAdmins(
                Notification.Type.SUBSTITUTION_ACCEPTED,
                "Замена подтверждена: " + request.getLessonInstance().getLessonDate(),
                msg,
                request.getId());

        notificationService.notifyTeacher(
                request.getOriginalTeacher(),
                Notification.Type.SUBSTITUTION_ACCEPTED,
                "Замена подтверждена: " + request.getLessonInstance().getLessonDate(),
                msg,
                request.getId());

        return request;
    }

    @Transactional
    public SubstitutionRequest declineSubstitution(Long requestId, Teacher respondingTeacher) {
        SubstitutionRequest request = substitutionRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Заявка на замену не найдена: " + requestId));

        if (!request.getCandidateTeacher().getId().equals(respondingTeacher.getId())) {
            throw new RuntimeException("Эта заявка адресована другому преподавателю");
        }
        if (request.getStatus() != SubstitutionRequest.Status.PENDING) {
            return request;
        }

        request.setStatus(SubstitutionRequest.Status.DECLINED);
        request.setRespondedAt(java.time.LocalDateTime.now());
        request = substitutionRequestRepository.save(request);

        notificationService.notifyAdmins(
                Notification.Type.SUBSTITUTION_DECLINED,
                "Отказ от замены: " + request.getLessonInstance().getLessonDate(),
                respondingTeacher.getFullName() + " отказался заменить "
                        + request.getOriginalTeacher().getFullName() + ". Подбирается следующий кандидат.",
                request.getId());

        Set<Long> alreadyProposed = substitutionRequestRepository
                .findByLessonInstanceIdOrderByPriorityRankAsc(request.getLessonInstance().getId())
                .stream().map(r -> r.getCandidateTeacher().getId()).collect(Collectors.toCollection(LinkedHashSet::new));

        LessonInstance instance = lessonInstanceRepository.findById(request.getLessonInstance().getId())
                .orElseThrow(() -> new RuntimeException("Занятие не найдено"));

        // После отказа сразу назначаем следующего кандидата автоматически
        autoAssignReplacement(instance, request.getSickLeave(), alreadyProposed);

        return request;
    }

    public List<SubstitutionRequest> findPendingForTeacher(Long teacherId) {
        return substitutionRequestRepository.findByCandidateTeacherIdAndStatus(teacherId, SubstitutionRequest.Status.PENDING);
    }

    public List<SubstitutionRequest> findAll() {
        return substitutionRequestRepository.findAllByOrderByCreatedAtDesc();
    }

    private static class Candidate {
        Teacher teacher;
        int priorityRank;
        String reason;
        boolean overload;
    }

    private List<Candidate> findCandidates(LessonInstance instance, Set<Long> excludeTeacherIds) {
        TeacherLoad originalLoad = instance.getSchedule().getTeacherLoad();
        Teacher originalTeacher = instance.getOriginalTeacher();
        LocalDate date = instance.getLessonDate();

        List<Candidate> result = new ArrayList<>();
        Set<Long> excluded = new LinkedHashSet<>(excludeTeacherIds);
        excluded.add(originalTeacher.getId());

        List<TeacherLoad> sameDiscipline = loadRepository.findByDisciplineIdAndAcademicYear(
                originalLoad.getDiscipline().getId(), originalLoad.getAcademicYear());
        LinkedHashSet<Long> candidateIds = new LinkedHashSet<>();
        for (TeacherLoad tl : sameDiscipline) {
            Long tId = tl.getTeacher().getId();
            if (excluded.contains(tId) || !candidateIds.add(tId)) continue;
            if (isBusyOrSick(tId, date, instance)) continue;
            Candidate c = new Candidate();
            c.teacher = tl.getTeacher();
            c.priorityRank = 1;
            c.reason = "Уже ведёт дисциплину «" + originalLoad.getDiscipline().getName() + "» у других групп";
            c.overload = tl.getRemainingHours() <= 0;
            result.add(c);
        }
        if (!result.isEmpty()) return result;

        if (originalTeacher.getDepartment() != null && !originalTeacher.getDepartment().isBlank()) {
            List<Teacher> sameDept = teacherRepository.findByDepartment(originalTeacher.getDepartment());
            for (Teacher t : sameDept) {
                if (excluded.contains(t.getId()) || !candidateIds.add(t.getId())) continue;
                if (isBusyOrSick(t.getId(), date, instance)) continue;
                Candidate c = new Candidate();
                c.teacher = t;
                c.priorityRank = 2;
                c.reason = "Та же кафедра (" + originalTeacher.getDepartment() + "), свободное окно в это время";
                c.overload = true;
                result.add(c);
            }
        }
        if (!result.isEmpty()) return result;

        for (Teacher t : teacherRepository.findAll()) {
            if (excluded.contains(t.getId()) || !candidateIds.add(t.getId())) continue;
            if (isBusyOrSick(t.getId(), date, instance)) continue;
            boolean hasReserve = loadRepository.findByTeacherIdAndAcademicYear(t.getId(), originalLoad.getAcademicYear())
                    .stream().anyMatch(l -> l.getRemainingHours() > 0);
            Candidate c = new Candidate();
            c.teacher = t;
            c.priorityRank = 3;
            c.reason = hasReserve
                    ? "Есть резерв часов в плановой нагрузке"
                    : "Свободен в это время (резерва часов нет — будет учтено как переработка)";
            c.overload = !hasReserve;
            result.add(c);
            if (result.size() >= 5) break;
        }

        return result;
    }

    private boolean isBusyOrSick(Long teacherId, LocalDate date, LessonInstance instance) {
        if (lessonInstanceService.isTeacherSickOnDate(teacherId, date)) return true;

        List<LessonInstance> busyToday = lessonInstanceRepository.findActiveByActualTeacherIdAndDate(teacherId, date);
        if (!busyToday.isEmpty()) {
            boolean overlap = busyToday.stream().anyMatch(li -> timesOverlap(li, instance));
            if (overlap) return true;
        }

        List<Schedule> candidateSchedules = scheduleRepository.findByTeacherLoadTeacherIdAndAcademicYear(
                teacherId, instance.getAcademicYear());
        for (Schedule s : candidateSchedules) {
            if (s.getDayOfWeek() != date.getDayOfWeek()) continue;
            if (s.getAcademicWeek() != null
                    && !s.getAcademicWeek().equals(lessonInstanceService.computeAcademicWeek(date, instance.getAcademicYear()))) {
                continue;
            }
            if (timeOverlap(s.getStartTime(), s.getEndTime(),
                    instance.getSchedule().getStartTime(), instance.getSchedule().getEndTime())) {
                return true;
            }
        }
        return false;
    }

    private boolean timesOverlap(LessonInstance a, LessonInstance b) {
        return timeOverlap(a.getSchedule().getStartTime(), a.getSchedule().getEndTime(),
                b.getSchedule().getStartTime(), b.getSchedule().getEndTime());
    }

    private boolean timeOverlap(java.time.LocalTime s1, java.time.LocalTime e1,
                                 java.time.LocalTime s2, java.time.LocalTime e2) {
        return s1.isBefore(e2) && s2.isBefore(e1);
    }
}
