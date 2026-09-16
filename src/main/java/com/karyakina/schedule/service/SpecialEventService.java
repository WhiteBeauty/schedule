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

    private static final int GROUP_MAX_WEEKLY_PAIRS = 18;
    private static final int SEARCH_HORIZON_DAYS = 70;

    private final SpecialEventRepository specialEventRepository;
    private final ScheduleRepository scheduleRepository;
    private final StudyGroupRepository groupRepository;
    private final DisciplineRepository disciplineRepository;
    private final TeacherLoadRepository loadRepository;
    private final ClassroomRepository classroomRepository;
    private final LessonInstanceService lessonInstanceService;
    private final LessonInstanceRepository lessonInstanceRepository;

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

        List<Schedule> allSchedules = scheduleRepository.findByAcademicYear(academicYear);
        List<SpecialEvent> groupEvents = specialEventRepository.findByGroupIdAndAcademicYear(groupId, academicYear);
        List<GenerationGrid.Room> rooms = GenerationGrid.rooms(classroomRepository.findAll());
        Tracker tracker = new Tracker(new ArrayList<>(allSchedules), groupEvents, rooms);

        Long ownLoadId = ownLoad != null ? ownLoad.getId() : null;
        Long ownDisciplineId = discipline != null ? discipline.getId() : null;

        List<SpecialEventDtos.MovedPair> moved = new ArrayList<>();
        List<SpecialEventDtos.UnresolvedConflict> unresolved = new ArrayList<>();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (GenerationGrid.dayIndex(date.getDayOfWeek()) < 0) continue;
            final LocalDate currentDate = date;
            int week = lessonInstanceService.computeAcademicWeek(date, academicYear);

            List<Schedule> dayConflicts = allSchedules.stream()
                    .filter(s -> s.getTeacherLoad() != null && s.getTeacherLoad().getGroup() != null
                            && s.getTeacherLoad().getGroup().getId().equals(groupId))
                    .filter(s -> s.getDayOfWeek() == currentDate.getDayOfWeek())
                    .filter(s -> s.getAcademicWeek() == null || s.getAcademicWeek().equals(week))
                    .filter(s -> !(ownLoadId != null && s.getTeacherLoad().getId().equals(ownLoadId)))
                    .filter(s -> !(ownDisciplineId != null && s.getTeacherLoad().getDiscipline() != null
                            && s.getTeacherLoad().getDiscipline().getId().equals(ownDisciplineId)))
                    .toList();

            for (Schedule s : dayConflicts) {
                lessonInstanceService.generateInstancesForDate(date, academicYear);
                lessonInstanceRepository.findByScheduleIdAndLessonDate(s.getId(), date).ifPresent(li -> {
                    LessonInstance cancelled = lessonInstanceService.cancelInstance(li.getId(), savedEvent.getNote(), adminName);
                    cancelled.setCancelledBySpecialEventId(savedEvent.getId());
                    lessonInstanceRepository.save(cancelled);
                });

                SearchOutcome outcome = tracker.search(s, date, group);
                if (!outcome.found()) {
                    unresolved.add(SpecialEventDtos.UnresolvedConflict.builder()
                            .teacherLoadId(s.getTeacherLoad().getId())
                            .disciplineName(s.getTeacherLoad().getDiscipline().getName())
                            .teacherName(s.getTeacherLoad().getTeacher() != null
                                    ? s.getTeacherLoad().getTeacher().getFullName() : "Не назначен")
                            .originalDate(date)
                            .originalDayOfWeek(GenerationGrid.dayName(GenerationGrid.dayIndex(date.getDayOfWeek())))
                            .originalStartTime(s.getStartTime())
                            .reason(diagnosisMessage(outcome))
                            .build());
                    continue;
                }
                Slot slot = outcome.slot();

                Schedule movedRow = Schedule.builder()
                        .teacherLoad(s.getTeacherLoad())
                        .dayOfWeek(slot.date().getDayOfWeek())
                        .startTime(GenerationGrid.start(slot.pairIdx()))
                        .endTime(GenerationGrid.end(slot.pairIdx()))
                        .classroom(slot.room())
                        .academicWeek(slot.week())
                        .academicYear(academicYear)
                        .semester(s.getSemester())
                        .rescheduledFromDate(date)
                        .rescheduledReason(savedEvent.getNote())
                        .specialEventId(savedEvent.getId())
                        .build();
                movedRow = scheduleRepository.save(movedRow);
                tracker.occupy(movedRow);

                moved.add(SpecialEventDtos.MovedPair.builder()
                        .scheduleId(movedRow.getId())
                        .teacherLoadId(s.getTeacherLoad().getId())
                        .disciplineName(s.getTeacherLoad().getDiscipline().getName())
                        .teacherName(s.getTeacherLoad().getTeacher() != null
                                ? s.getTeacherLoad().getTeacher().getFullName() : "Не назначен")
                        .fromDate(date)
                        .fromDayOfWeek(GenerationGrid.dayName(GenerationGrid.dayIndex(date.getDayOfWeek())))
                        .fromStartTime(s.getStartTime())
                        .toDate(slot.date())
                        .toDayOfWeek(GenerationGrid.dayName(GenerationGrid.dayIndex(slot.date().getDayOfWeek())))
                        .toStartTime(movedRow.getStartTime())
                        .toEndTime(movedRow.getEndTime())
                        .classroom(slot.room())
                        .build());
            }
        }

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
                .build();
    }

    @Transactional
    public Schedule resolveManually(Long eventId, Long teacherLoadId, LocalDate fromDate, LocalDate toDate,
                                    int pairIdx, String classroom) {
        SpecialEvent event = specialEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Событие не найдено: " + eventId));
        TeacherLoad load = loadRepository.findById(teacherLoadId)
                .orElseThrow(() -> new IllegalArgumentException("Нагрузка не найдена: " + teacherLoadId));
        int week = lessonInstanceService.computeAcademicWeek(toDate, event.getAcademicYear());

        DayOfWeek targetDay = toDate.getDayOfWeek();
        java.time.LocalTime targetStart = GenerationGrid.start(pairIdx);
        Long teacherId = load.getTeacher() != null ? load.getTeacher().getId() : null;
        Long groupId = load.getGroup() != null ? load.getGroup().getId() : null;
        List<Schedule> sameSlot = scheduleRepository.findByAcademicYear(event.getAcademicYear()).stream()
                .filter(s -> s.getDayOfWeek() == targetDay && s.getStartTime().equals(targetStart))
                .filter(s -> s.getAcademicWeek() == null || s.getAcademicWeek().equals(week))
                .toList();
        for (Schedule other : sameSlot) {
            if (teacherId != null && other.getTeacherLoad() != null && other.getTeacherLoad().getTeacher() != null
                    && teacherId.equals(other.getTeacherLoad().getTeacher().getId())) {
                throw new IllegalStateException("Преподаватель " + other.getTeacherLoad().getTeacher().getFullName()
                        + " уже занят в это время (" + targetDay + ", " + targetStart + ").");
            }
            if (groupId != null && other.getTeacherLoad() != null && other.getTeacherLoad().getGroup() != null
                    && groupId.equals(other.getTeacherLoad().getGroup().getId())) {
                throw new IllegalStateException("У группы " + other.getTeacherLoad().getGroup().getName()
                        + " уже есть пара в это время (" + targetDay + ", " + targetStart + ").");
            }
            if (classroom != null && !classroom.isBlank() && classroom.equals(other.getClassroom())) {
                throw new IllegalStateException("Аудитория " + classroom
                        + " уже занята в это время (" + targetDay + ", " + targetStart + ").");
            }
        }

        Schedule schedule = Schedule.builder()
                .teacherLoad(load)
                .dayOfWeek(toDate.getDayOfWeek())
                .startTime(GenerationGrid.start(pairIdx))
                .endTime(GenerationGrid.end(pairIdx))
                .classroom(classroom)
                .academicWeek(week)
                .academicYear(event.getAcademicYear())
                .rescheduledFromDate(fromDate)
                .rescheduledReason(event.getNote())
                .build();
        return scheduleRepository.save(schedule);
    }

    public List<SpecialEvent> findForGroup(Long groupId, Integer academicYear) {
        return specialEventRepository.findByGroupIdAndAcademicYear(groupId, academicYear);
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

    private String diagnosisMessage(SearchOutcome outcome) {
        StringBuilder sb = new StringBuilder("Не нашлось свободного слота в пределах "
                + SEARCH_HORIZON_DAYS + " дней. Причины отказа: ");
        List<String> parts = new ArrayList<>();
        if (outcome.slotsTeacherBusy() > 0) {
            parts.add("преподаватель занят (" + outcome.slotsTeacherBusy() + " раз)");
        }
        if (outcome.slotsGroupBusy() > 0) {
            parts.add("у группы уже пара в это время (" + outcome.slotsGroupBusy() + " раз)");
        }
        if (outcome.slotsNoRoom() > 0) {
            parts.add("нет подходящей свободной аудитории (" + outcome.slotsNoRoom() + " раз)");
        }
        if (outcome.datesSkippedWeekCap() > 0) {
            parts.add("у группы лимит 18 пар/нед уже выбран (" + outcome.datesSkippedWeekCap() + " недель)");
        }
        if (outcome.datesSkippedBlocked() > 0) {
            parts.add("день занят другим экзаменом/практикой (" + outcome.datesSkippedBlocked() + " дней)");
        }
        if (parts.isEmpty()) {
            return sb.append("не нашлось ни одного рабочего дня в периоде поиска "
                    + "(возможно, конец семестра слишком близко к этой дате).").toString();
        }
        return sb.append(String.join("; ", parts)).append(".").toString();
    }

    private record Slot(LocalDate date, int pairIdx, String room, int week) {
    }

    private record SearchOutcome(Slot slot, int datesSkippedBlocked, int datesSkippedWeekCap,
                                 int slotsTeacherBusy, int slotsGroupBusy, int slotsNoRoom) {
        boolean found() {
            return slot != null;
        }
    }

    private static final class Tracker {
        private final List<Schedule> active;
        private final List<SpecialEvent> groupEvents;
        private final List<GenerationGrid.Room> rooms;

        Tracker(List<Schedule> active, List<SpecialEvent> groupEvents, List<GenerationGrid.Room> rooms) {
            this.active = active;
            this.groupEvents = groupEvents;
            this.rooms = rooms;
        }

        void occupy(Schedule s) {
            active.add(s);
        }

        SearchOutcome search(Schedule original, LocalDate originalDate, StudyGroup group) {
            long teacherId = original.getTeacherLoad().getTeacher() != null
                    ? original.getTeacherLoad().getTeacher().getId() : -1;
            long groupId = group.getId();
            int academicYear = original.getAcademicYear();
            String disciplineName = original.getTeacherLoad().getDiscipline().getName();
            int studentCount = group.getStudentCount() == null ? 0 : group.getStudentCount();

            LocalDate searchFrom = originalDate.plusDays(1);
            LocalDate today = LocalDate.now();
            if (searchFrom.isBefore(today)) {
                searchFrom = today;
            }
            int originalSemester = original.getSemester() != null
                    ? original.getSemester() : AcademicYearUtil.semesterOfDate(originalDate);
            LocalDate semesterEnd = AcademicYearUtil.semesterEnd(originalSemester, academicYear);
            LocalDate searchTo = originalDate.plusDays(SEARCH_HORIZON_DAYS);
            if (semesterEnd.isBefore(searchTo)) {
                searchTo = semesterEnd;
            }

            int datesSkippedBlocked = 0, datesSkippedWeekCap = 0;
            int slotsTeacherBusy = 0, slotsGroupBusy = 0, slotsNoRoom = 0;

            for (LocalDate d = searchFrom; !d.isAfter(searchTo); d = d.plusDays(1)) {
                if (GenerationGrid.dayIndex(d.getDayOfWeek()) < 0) continue;
                if (AcademicYearUtil.isVacation(d)) continue;
                if (isGroupBlocked(groupId, d)) { datesSkippedBlocked++; continue; }
                int week = weekOf(d, academicYear);
                if (groupWeekCount(groupId, week) >= GROUP_MAX_WEEKLY_PAIRS) { datesSkippedWeekCap++; continue; }

                for (int pairIdx = 0; pairIdx < GenerationGrid.pairsPerDay(); pairIdx++) {
                    if (isTeacherBusy(teacherId, d.getDayOfWeek(), pairIdx, week)) { slotsTeacherBusy++; continue; }
                    if (isGroupBusy(groupId, d.getDayOfWeek(), pairIdx, week)) { slotsGroupBusy++; continue; }

                    String room = original.getClassroom();
                    if (room == null || isRoomBusy(room, d.getDayOfWeek(), pairIdx, week)) {
                        room = findFreeRoom(d.getDayOfWeek(), pairIdx, week, studentCount, disciplineName);
                    }
                    if (room == null) { slotsNoRoom++; continue; }

                    return new SearchOutcome(new Slot(d, pairIdx, room, week), datesSkippedBlocked,
                            datesSkippedWeekCap, slotsTeacherBusy, slotsGroupBusy, slotsNoRoom);
                }
            }
            return new SearchOutcome(null, datesSkippedBlocked, datesSkippedWeekCap,
                    slotsTeacherBusy, slotsGroupBusy, slotsNoRoom);
        }

        private int weekOf(LocalDate date, int academicYear) {
            LocalDate start = LocalDate.of(academicYear, 9, 1);
            if (date.isBefore(start)) {
                start = LocalDate.of(academicYear - 1, 9, 1);
            }
            java.time.temporal.WeekFields iso = java.time.temporal.WeekFields.ISO;
            long weeks = java.time.temporal.ChronoUnit.WEEKS.between(
                    start.with(iso.getFirstDayOfWeek()), date.with(iso.getFirstDayOfWeek()));
            return (int) weeks + 1;
        }

        private boolean isGroupBlocked(long groupId, LocalDate date) {
            return groupEvents.stream().anyMatch(e -> !date.isBefore(e.getStartDate()) && !date.isAfter(e.getEndDate()));
        }

        private boolean isTeacherBusy(long teacherId, DayOfWeek day, int pairIdx, int week) {
            return active.stream().anyMatch(s -> s.getTeacherLoad().getTeacher() != null
                    && s.getTeacherLoad().getTeacher().getId() == teacherId
                    && s.getDayOfWeek() == day
                    && GenerationGrid.pairIndex(s.getStartTime()) == pairIdx
                    && (s.getAcademicWeek() == null || s.getAcademicWeek() == week));
        }

        private boolean isGroupBusy(long groupId, DayOfWeek day, int pairIdx, int week) {
            return active.stream().anyMatch(s -> s.getTeacherLoad().getGroup() != null
                    && s.getTeacherLoad().getGroup().getId() == groupId
                    && s.getDayOfWeek() == day
                    && GenerationGrid.pairIndex(s.getStartTime()) == pairIdx
                    && (s.getAcademicWeek() == null || s.getAcademicWeek() == week));
        }

        private boolean isRoomBusy(String room, DayOfWeek day, int pairIdx, int week) {
            return active.stream().anyMatch(s -> room.equals(s.getClassroom())
                    && s.getDayOfWeek() == day
                    && GenerationGrid.pairIndex(s.getStartTime()) == pairIdx
                    && (s.getAcademicWeek() == null || s.getAcademicWeek() == week));
        }

        private int groupWeekCount(long groupId, int week) {
            return (int) active.stream().filter(s -> s.getTeacherLoad().getGroup() != null
                    && s.getTeacherLoad().getGroup().getId() == groupId
                    && (s.getAcademicWeek() == null || s.getAcademicWeek() == week))
                    .count();
        }

        private String findFreeRoom(DayOfWeek day, int pairIdx, int week, int studentCount, String disciplineName) {
            for (GenerationGrid.Room room : rooms) {
                if (room.capacity() > 0 && studentCount > room.capacity()) continue;
                if (!room.isOpenFor(disciplineName)) continue;
                if (!isRoomBusy(room.name(), day, pairIdx, week)) {
                    return room.name();
                }
            }
            return null;
        }
    }
}
