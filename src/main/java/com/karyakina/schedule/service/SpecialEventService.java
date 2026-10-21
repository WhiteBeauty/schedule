package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.dto.SpecialEventDtos;
import com.karyakina.schedule.repository.*;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpecialEventService {

    private final SpecialEventRepository specialEventRepository;
    private final ScheduleRepository scheduleRepository;
    private final StudyGroupRepository groupRepository;
    private final DisciplineRepository disciplineRepository;
    private final TeacherLoadRepository loadRepository;
    private final ClassroomRepository classroomRepository;
    private final LessonInstanceService lessonInstanceService;
    private final LessonInstanceRepository lessonInstanceRepository;
    private final UnresolvedRescheduleRepository unresolvedRescheduleRepository;
    private final CalendarSlotFinder slotFinder;
    private final HoursRecalculationService hoursRecalculationService;

    @Transactional
    public SpecialEventDtos.Result assignEvent(SpecialEvent.Type type, Long groupId, Long disciplineId,
                                               Long teacherLoadId, LocalDate startDate, LocalDate endDate,
                                               Integer academicYear, String adminName) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("Дата окончания раньше даты начала");
        }
        StudyGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Группа не найдена: " + groupId));
        Discipline discipline = disciplineId != null ? disciplineRepository.findById(disciplineId).orElse(null) : null;
        TeacherLoad ownLoad = teacherLoadId != null ? loadRepository.findById(teacherLoadId).orElse(null) : null;

        List<SpecialEvent> groupEvents = specialEventRepository.findByGroupIdAndAcademicYear(groupId, academicYear);
        SpecialEvent overlapping = groupEvents.stream()
                .filter(e -> !e.getEndDate().isBefore(startDate) && !e.getStartDate().isAfter(endDate))
                .findFirst().orElse(null);
        if (overlapping != null) {
            throw new IllegalArgumentException("У группы «" + group.getName() + "» уже назначено «"
                    + typeLabel(overlapping.getType()) + "» на "
                    + (overlapping.getStartDate().equals(overlapping.getEndDate())
                            ? overlapping.getStartDate().toString()
                            : overlapping.getStartDate() + " — " + overlapping.getEndDate())
                    + ". Сначала отмените его, если нужно назначить другое событие на эти даты.");
        }

        SpecialEvent event = SpecialEvent.builder()
                .group(group)
                .type(type)
                .discipline(discipline)
                .teacherLoad(ownLoad)
                .startDate(startDate)
                .endDate(endDate)
                .academicYear(academicYear)
                .createdBy(adminName)
                .note(buildNote(type, discipline))
                .build();
        final SpecialEvent savedEvent = specialEventRepository.save(event);
        groupEvents.add(savedEvent);

        Long ownLoadId = ownLoad != null ? ownLoad.getId() : null;
        Long ownDisciplineId = discipline != null ? discipline.getId() : null;

        List<SpecialEventDtos.MovedPair> moved = new ArrayList<>();
        List<SpecialEventDtos.UnresolvedConflict> unresolved = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        CalendarSlotFinder.Calendar calendar = slotFinder.open(academicYear, startDate,
                AcademicYearUtil.semesterEnd(2, academicYear));
        int weekCap = CalendarSlotFinder.weekCap(group);

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (!AcademicYearUtil.isWorkDay(date) || AcademicYearUtil.isVacation(date)) continue;
            int semester = AcademicYearUtil.semesterOfDate(date);
            if (semester == 0) continue;
            final LocalDate currentDate = date;

            List<LessonInstance> dayConflicts = lessonInstanceService.generateInstancesForDate(date, academicYear)
                    .stream()
                    .filter(li -> li.getSchedule() != null && li.getSchedule().getTeacherLoad() != null)
                    .filter(li -> li.getStatus() == LessonInstance.Status.PLANNED
                            || li.getStatus() == LessonInstance.Status.CONFIRMED
                            || li.getStatus() == LessonInstance.Status.REPLACED)
                    .filter(li -> {
                        TeacherLoad l = li.getSchedule().getTeacherLoad();
                        return l.getGroup() != null && l.getGroup().getId().equals(groupId);
                    })
                    .filter(li -> !(ownLoadId != null && li.getSchedule().getTeacherLoad().getId().equals(ownLoadId)))
                    .filter(li -> !(ownDisciplineId != null && li.getSchedule().getTeacherLoad().getDiscipline() != null
                            && li.getSchedule().getTeacherLoad().getDiscipline().getId().equals(ownDisciplineId)))
                    .sorted(java.util.Comparator.comparing(li -> li.getSchedule().getStartTime()))
                    .toList();

            for (LessonInstance li : dayConflicts) {
                Schedule s = li.getSchedule();
                TeacherLoad load = s.getTeacherLoad();
                LessonInstance cancelled = lessonInstanceService.cancelInstance(li.getId(), savedEvent.getNote(), adminName);
                cancelled.setCancelledBySpecialEventId(savedEvent.getId());
                lessonInstanceRepository.save(cancelled);
                calendar.release(s.getId(), currentDate);

                Long teacherId = load.getTeacher() != null ? load.getTeacher().getId() : null;
                LocalDate searchFrom = currentDate.with(DayOfWeek.MONDAY);
                CalendarSlotFinder.Outcome outcome = calendar.find(new CalendarSlotFinder.Request(load, teacherId,
                        searchFrom, AcademicYearUtil.semesterEnd(semester, academicYear), semester,
                        s.getClassroom(), GenerationGrid.pairIndex(s.getStartTime()), true));

                String teacherName = load.getTeacher() != null ? load.getTeacher().getFullName() : "Не назначен";
                String dayName = GenerationGrid.dayName(GenerationGrid.dayIndex(currentDate.getDayOfWeek()));
                if (!outcome.found()) {
                    String reason = "Не нашлось свободного слота до конца семестра: "
                            + outcome.diagnostics().describe(weekCap) + ".";
                    unresolved.add(SpecialEventDtos.UnresolvedConflict.builder()
                            .teacherLoadId(load.getId())
                            .disciplineName(load.getDiscipline().getName())
                            .teacherName(teacherName)
                            .originalDate(currentDate)
                            .originalDayOfWeek(dayName)
                            .originalStartTime(s.getStartTime())
                            .reason(reason)
                            .build());
                    unresolvedRescheduleRepository.save(UnresolvedReschedule.builder()
                            .specialEventId(savedEvent.getId())
                            .teacherLoadId(load.getId())
                            .groupId(group.getId())
                            .groupName(group.getName())
                            .disciplineName(load.getDiscipline().getName())
                            .teacherName(teacherName)
                            .originalDate(currentDate)
                            .originalDayOfWeek(dayName)
                            .originalStartTime(s.getStartTime())
                            .hours(li.getDurationHours())
                            .reason(reason)
                            .academicYear(academicYear)
                            .build());
                    continue;
                }
                CalendarSlotFinder.Slot slot = outcome.slot();
                Schedule movedRow = scheduleRepository.save(Schedule.builder()
                        .teacherLoad(load)
                        .dayOfWeek(slot.date().getDayOfWeek())
                        .startTime(GenerationGrid.start(slot.pairIdx()))
                        .endTime(GenerationGrid.end(slot.pairIdx()))
                        .classroom(slot.room())
                        .academicWeek(slot.week())
                        .academicYear(academicYear)
                        .semester(semester)
                        .rescheduledFromDate(currentDate)
                        .rescheduledReason(savedEvent.getNote())
                        .specialEventId(savedEvent.getId())
                        .build());
                calendar.occupy(slot, load, teacherId, movedRow.getId());
                if (slot.createsWindow()) {
                    warnings.add(load.getDiscipline().getName() + " перенесена на " + slot.date() + ", "
                            + (slot.pairIdx() + 1) + "-я пара: без окна у группы поставить не удалось.");
                }

                moved.add(SpecialEventDtos.MovedPair.builder()
                        .scheduleId(movedRow.getId())
                        .teacherLoadId(load.getId())
                        .disciplineName(load.getDiscipline().getName())
                        .teacherName(teacherName)
                        .fromDate(currentDate)
                        .fromDayOfWeek(dayName)
                        .fromStartTime(s.getStartTime())
                        .toDate(slot.date())
                        .toDayOfWeek(GenerationGrid.dayName(GenerationGrid.dayIndex(slot.date().getDayOfWeek())))
                        .toStartTime(movedRow.getStartTime())
                        .toEndTime(movedRow.getEndTime())
                        .classroom(slot.room())
                        .build());
            }
        }

        // Пары, которым не нашлось слота, пробуем покрыть динамическим пересчётом часов этих же дисциплин:
        // он учитывает запас расписания до конца семестра (лишние пары снимаются, недостающие — добавляются).
        if (!unresolved.isEmpty()) {
            java.util.Map<Integer, java.util.Set<Long>> loadsBySemester = new java.util.HashMap<>();
            for (SpecialEventDtos.UnresolvedConflict u : unresolved) {
                int sem = AcademicYearUtil.semesterOfDate(u.getOriginalDate());
                if (sem > 0) loadsBySemester.computeIfAbsent(sem, k -> new java.util.HashSet<>()).add(u.getTeacherLoadId());
            }
            java.util.Set<Long> stillUncovered = new java.util.HashSet<>();
            for (var e : loadsBySemester.entrySet()) {
                try {
                    HoursRecalculationService.Result r = hoursRecalculationService.recalculate(academicYear, e.getKey(),
                            e.getValue(), false);
                    stillUncovered.addAll(r.uncoveredLoadIds());
                    if (!r.added().isEmpty() || !r.cancelled().isEmpty()) {
                        warnings.add("Пересчёт часов: добавлено пар " + r.added().size() + ", снято лишних "
                                + r.cancelled().size() + " (по дисциплинам, которые не удалось перенести напрямую).");
                    }
                } catch (Exception ex) {
                    log.warn("Пересчёт часов после события не выполнен: {}", ex.getMessage());
                    stillUncovered.addAll(e.getValue());
                }
            }
            int before = unresolved.size();
            unresolved.removeIf(u -> !stillUncovered.contains(u.getTeacherLoadId()));
            if (before > unresolved.size()) {
                warnings.add("Часы " + (before - unresolved.size()) + " пар(ы) покрыты пересчётом до конца семестра — "
                        + "в «облако» они не попали.");
            }
        }

        log.info("Событие {} «{}» для группы {} ({} — {}): перенесено {}, в «облако» {}, предупреждений {}",
                savedEvent.getId(), typeLabel(type), group.getName(), startDate, endDate, moved.size(),
                unresolved.size(), warnings.size());
        unresolved.forEach(u -> log.info("Событие {}: не перенесена «{}» от {} {} — {}", savedEvent.getId(),
                u.getDisciplineName(), u.getOriginalDate(), u.getOriginalStartTime(), u.getReason()));
        warnings.forEach(w -> log.info("Событие {}: {}", savedEvent.getId(), w));
        return SpecialEventDtos.Result.builder()
                .eventId(savedEvent.getId())
                .type(type.name())
                .typeLabel(typeLabel(type))
                .groupName(group.getName())
                .disciplineName(discipline != null ? discipline.getName() : null)
                .startDate(startDate)
                .endDate(endDate)
                .moved(moved)
                .unresolved(unresolved)
                .warnings(warnings)
                .build();
    }

    @Transactional
    public Schedule resolveManually(Long eventId, Long teacherLoadId, LocalDate fromDate, LocalDate toDate,
                                    int pairIdx, String classroom) {
        SpecialEvent event = specialEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Событие не найдено: " + eventId));
        TeacherLoad load = loadRepository.findById(teacherLoadId)
                .orElseThrow(() -> new IllegalArgumentException("Нагрузка не найдена: " + teacherLoadId));
        if (pairIdx < 0 || pairIdx >= GenerationGrid.pairsPerDay()) {
            throw new IllegalArgumentException("Неверный номер пары: " + (pairIdx + 1));
        }
        if (!AcademicYearUtil.isWorkDay(toDate) || AcademicYearUtil.isVacation(toDate)) {
            throw new IllegalStateException(toDate + " — выходной, праздник или каникулы.");
        }
        int academicYear = event.getAcademicYear();
        int week = AcademicYearUtil.academicWeek(toDate, academicYear);
        int semester = AcademicYearUtil.semesterOfDate(toDate);

        CalendarSlotFinder.Calendar calendar = slotFinder.open(academicYear, toDate, toDate);
        Long teacherId = load.getTeacher() != null ? load.getTeacher().getId() : null;
        Long groupId = load.getGroup() != null ? load.getGroup().getId() : null;
        if (groupId != null && calendar.isGroupBlocked(groupId, toDate)) {
            throw new IllegalStateException("У группы в этот день экзамен или практика.");
        }
        if (groupId != null && calendar.pairsOfGroup(groupId, toDate).contains(pairIdx)) {
            throw new IllegalStateException("У группы уже есть пара в это время (" + toDate + ", "
                    + (pairIdx + 1) + "-я пара).");
        }
        if (calendar.isTeacherBusy(teacherId, toDate, pairIdx)) {
            throw new IllegalStateException("Преподаватель уже занят в это время (" + toDate + ", "
                    + (pairIdx + 1) + "-я пара).");
        }
        if (calendar.isTeacherAbsent(teacherId, toDate)) {
            throw new IllegalStateException("Преподаватель в этот день отсутствует (больничный).");
        }
        if (classroom != null && !classroom.isBlank() && calendar.isRoomBusy(classroom, toDate, pairIdx)) {
            throw new IllegalStateException("Аудитория " + classroom + " уже занята в это время.");
        }

        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .teacherLoad(load)
                .dayOfWeek(toDate.getDayOfWeek())
                .startTime(GenerationGrid.start(pairIdx))
                .endTime(GenerationGrid.end(pairIdx))
                .classroom(classroom)
                .academicWeek(week)
                .academicYear(academicYear)
                .semester(semester == 0 ? null : semester)
                .rescheduledFromDate(fromDate)
                .rescheduledReason(event.getNote())
                .specialEventId(event.getId())
                .build());

        unresolvedRescheduleRepository.findByAcademicYearOrderByOriginalDateAsc(academicYear).stream()
                .filter(u -> eventId.equals(u.getSpecialEventId()) && teacherLoadId.equals(u.getTeacherLoadId())
                        && (fromDate == null || fromDate.equals(u.getOriginalDate())))
                .findFirst()
                .ifPresent(unresolvedRescheduleRepository::delete);
        return schedule;
    }

    public List<SpecialEvent> findForGroup(Long groupId, Integer academicYear) {
        return specialEventRepository.findByGroupIdAndAcademicYear(groupId, academicYear);
    }

    public List<UnresolvedReschedule> findUnresolved(Integer academicYear) {
        return unresolvedRescheduleRepository.findByAcademicYearOrderByOriginalDateAsc(academicYear);
    }

    @Transactional
    public void dismissUnresolved(Long id) {
        unresolvedRescheduleRepository.deleteById(id);
    }

    @Transactional
    public void deleteEvent(Long eventId) {
        SpecialEvent event = specialEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Событие не найдено: " + eventId));

        List<LessonInstance> cancelledByThis = lessonInstanceRepository.findByCancelledBySpecialEventId(eventId);
        for (LessonInstance li : cancelledByThis) {
            li.setStatus(LessonInstance.Status.PLANNED);
            li.setCancelledAt(null);
            li.setNote(null);
            li.setCancelledBySpecialEventId(null);
            lessonInstanceRepository.save(li);
        }

        List<Schedule> movedByThis = scheduleRepository.findBySpecialEventId(eventId);
        scheduleRepository.deleteAll(movedByThis);

        unresolvedRescheduleRepository.deleteBySpecialEventId(eventId);

        specialEventRepository.delete(event);
    }

    private String buildNote(SpecialEvent.Type type, Discipline discipline) {
        return switch (type) {
            case EXAM -> "Экзамен" + (discipline != null ? ": " + discipline.getName() : "");
            case PRODUCTION_PRACTICE -> "Производственная практика";
            case STUDY_PRACTICE -> "Учебная практика";
            case DRIVING -> "Вождение";
        };
    }

    private String typeLabel(SpecialEvent.Type type) {
        return switch (type) {
            case EXAM -> "Экзамен";
            case PRODUCTION_PRACTICE -> "Производственная практика";
            case STUDY_PRACTICE -> "Учебная практика";
            case DRIVING -> "Вождение";
        };
    }
}
