package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.dto.SpecialEventDtos;
import com.karyakina.schedule.repository.*;
import com.karyakina.schedule.service.generator.GenerationGrid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Движок автопереноса пар для экзаменов, учебной/производственной практики и вождения
 * (ТЗ п.4-9). Общая логика для всех четырёх случаев: на период [startDate, endDate] у
 * группы блокируются обычные пары, каждая конфликтующая пара автоматически переставляется
 * на ближайший свободный слот в БУДУЩЕМ (не раньше сегодня и не раньше исходной даты —
 * "назад" пары не переносятся), с учётом занятости преподавателя/аудитории, недельного
 * лимита группы (18 пар) и уже других запланированных у группы блокировок. То, что
 * перенести не удалось, возвращается администратору как список конфликтов — он либо
 * переносит вручную через обычное редактирование пары (валидация лимита уже есть), либо
 * позже перезапускает попытку.
 *
 * <p>Технически: исходная пара НЕ удаляется из недельного шаблона {@link Schedule} (она
 * по-прежнему действует в другие недели/годы) — блокируется только конкретное занятие
 * на эту дату через {@link LessonInstance} (см. {@link LessonInstanceService#cancelInstance}).
 * "Перенесённая" пара — это НОВАЯ запись {@link Schedule} с {@code academicWeek}, указывающим
 * ровно на одну неделю (разовое исключение), и {@code rescheduledFromDate} — для подсветки.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecialEventService {

    private static final int GROUP_MAX_WEEKLY_PAIRS = 18;
    /** Не искать слот дальше ~2 месяцев вперёд — иначе можно уйти за пределы семестра/года без пользы. */
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

        // Одно присваивание делает переменную effectively final — её захватывает
        // лямбда ниже (cancelInstance в ifPresent).
        SpecialEvent event = specialEventRepository.save(SpecialEvent.builder()
                .group(group)
                .type(type)
                .discipline(discipline)
                .teacherLoad(ownLoad)
                .startDate(startDate)
                .endDate(endDate)
                .academicYear(academicYear)
                .createdBy(adminName)
                .note(buildNote(type, discipline))
                .build());

        List<Schedule> allSchedules = scheduleRepository.findByAcademicYear(academicYear);
        List<SpecialEvent> groupEvents = specialEventRepository.findByGroupIdAndAcademicYear(groupId, academicYear);
        List<GenerationGrid.Room> rooms = GenerationGrid.rooms(classroomRepository.findAll());
        Tracker tracker = new Tracker(new ArrayList<>(allSchedules), groupEvents, rooms);

        Long ownLoadId = ownLoad != null ? ownLoad.getId() : null;
        Long ownDisciplineId = discipline != null ? discipline.getId() : null;

        List<SpecialEventDtos.MovedPair> moved = new ArrayList<>();
        List<SpecialEventDtos.UnresolvedConflict> unresolved = new ArrayList<>();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (GenerationGrid.dayIndex(date.getDayOfWeek()) < 0) continue; // воскресенье
            int week = lessonInstanceService.computeAcademicWeek(date, academicYear);

            // `date` — счётчик цикла, он не effectively final; для лямбд стрима
            // ниже нужна локальная неизменяемая копия.
            LocalDate day = date;
            List<Schedule> dayConflicts = allSchedules.stream()
                    .filter(s -> s.getTeacherLoad() != null && s.getTeacherLoad().getGroup() != null
                            && s.getTeacherLoad().getGroup().getId().equals(groupId))
                    .filter(s -> s.getDayOfWeek() == day.getDayOfWeek())
                    .filter(s -> s.getAcademicWeek() == null || s.getAcademicWeek().equals(week))
                    .filter(s -> !(ownLoadId != null && s.getTeacherLoad().getId().equals(ownLoadId)))
                    .filter(s -> !(ownDisciplineId != null && s.getTeacherLoad().getDiscipline() != null
                            && s.getTeacherLoad().getDiscipline().getId().equals(ownDisciplineId)))
                    .toList();

            for (Schedule s : dayConflicts) {
                // Блокируем исходное занятие именно на эту дату (шаблон на другие недели/годы не трогаем).
                lessonInstanceService.generateInstancesForDate(date, academicYear);
                lessonInstanceRepository.findByScheduleIdAndLessonDate(s.getId(), date).ifPresent(li ->
                        lessonInstanceService.cancelInstance(li.getId(), event.getNote(), adminName));

                Slot slot = tracker.findFreeSlot(s, date, group);
                if (slot == null) {
                    unresolved.add(SpecialEventDtos.UnresolvedConflict.builder()
                            .teacherLoadId(s.getTeacherLoad().getId())
                            .disciplineName(s.getTeacherLoad().getDiscipline().getName())
                            .teacherName(s.getTeacherLoad().getTeacher() != null
                                    ? s.getTeacherLoad().getTeacher().getFullName() : "Не назначен")
                            .originalDate(date)
                            .originalDayOfWeek(GenerationGrid.dayName(GenerationGrid.dayIndex(date.getDayOfWeek())))
                            .originalStartTime(s.getStartTime())
                            .reason("Не нашлось свободного слота в пределах " + SEARCH_HORIZON_DAYS
                                    + " дней с учётом занятости преподавателя/аудитории и лимита 18 пар/нед у группы")
                            .build());
                    continue;
                }

                Schedule movedRow = Schedule.builder()
                        .teacherLoad(s.getTeacherLoad())
                        .dayOfWeek(slot.date().getDayOfWeek())
                        .startTime(GenerationGrid.start(slot.pairIdx()))
                        .endTime(GenerationGrid.end(slot.pairIdx()))
                        .classroom(slot.room())
                        .academicWeek(slot.week())
                        .academicYear(academicYear)
                        .rescheduledFromDate(date)
                        .rescheduledReason(event.getNote())
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
                .eventId(event.getId())
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

    /**
     * Ручное разрешение одного конфликта, который не смог перенестись автоматически —
     * та же проверка занятости, что и в обычном ручном добавлении пары
     * ({@code ScheduleService.createSchedule}, включая лимит 18 пар/нед), плюс отметка
     * "перенесено" для подсветки.
     */
    @Transactional
    public Schedule resolveManually(Long eventId, Long teacherLoadId, LocalDate fromDate, LocalDate toDate,
                                    int pairIdx, String classroom) {
        SpecialEvent event = specialEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Событие не найдено: " + eventId));
        TeacherLoad load = loadRepository.findById(teacherLoadId)
                .orElseThrow(() -> new IllegalArgumentException("Нагрузка не найдена: " + teacherLoadId));
        int week = lessonInstanceService.computeAcademicWeek(toDate, event.getAcademicYear());

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

    private record Slot(LocalDate date, int pairIdx, String room, int week) {
    }

    /** Учёт занятости на время одного прогона переноса — линейный поиск по небольшому набору данных. */
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

        /** Ищет первый подходящий свободный слот НАЧИНАЯ СО СЛЕДУЮЩЕГО ДНЯ после исходной даты, и не раньше сегодня. */
        Slot findFreeSlot(Schedule original, LocalDate originalDate, StudyGroup group) {
            long teacherId = original.getTeacherLoad().getTeacher() != null
                    ? original.getTeacherLoad().getTeacher().getId() : -1;
            long groupId = group.getId();
            int academicYear = original.getAcademicYear();
            String disciplineName = original.getTeacherLoad().getDiscipline().getName();
            int studentCount = group.getStudentCount() == null ? 0 : group.getStudentCount();

            LocalDate searchFrom = originalDate.plusDays(1);
            LocalDate today = LocalDate.now();
            if (searchFrom.isBefore(today)) {
                searchFrom = today; // "назад" не переносим — минимум с сегодня
            }
            LocalDate searchTo = originalDate.plusDays(SEARCH_HORIZON_DAYS);

            for (LocalDate d = searchFrom; !d.isAfter(searchTo); d = d.plusDays(1)) {
                if (GenerationGrid.dayIndex(d.getDayOfWeek()) < 0) continue;
                if (isGroupBlocked(groupId, d)) continue; // другой экзамен/практика в этот день
                int week = weekOf(d, academicYear);
                if (groupWeekCount(groupId, week) >= GROUP_MAX_WEEKLY_PAIRS) continue;

                for (int pairIdx = 0; pairIdx < GenerationGrid.pairsPerDay(); pairIdx++) {
                    if (isTeacherBusy(teacherId, d.getDayOfWeek(), pairIdx, week)) continue;
                    if (isGroupBusy(groupId, d.getDayOfWeek(), pairIdx, week)) continue;

                    String room = original.getClassroom();
                    if (room == null || isRoomBusy(room, d.getDayOfWeek(), pairIdx, week)) {
                        room = findFreeRoom(d.getDayOfWeek(), pairIdx, week, studentCount, disciplineName);
                    }
                    if (room == null) continue;

                    return new Slot(d, pairIdx, room, week);
                }
            }
            return null;
        }

        private int weekOf(LocalDate date, int academicYear) {
            // Дублирует LessonInstanceService.computeAcademicWeek (см. там комментарий про
            // ISO-неделю) — не хотим тянуть Spring-бин внутрь статического поиска слотов.
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
