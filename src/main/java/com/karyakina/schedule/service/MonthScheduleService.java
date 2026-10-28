package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.SpecialEvent;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.dto.MonthEventDto;
import com.karyakina.schedule.dto.MonthLessonDto;
import com.karyakina.schedule.dto.MonthScheduleDto;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.SpecialEventRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.util.AcademicYearUtil;
import com.karyakina.schedule.util.ScheduleOccurrence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final LessonInstanceRepository instanceRepository;
    private final SpecialEventRepository specialEventRepository;

    public MonthScheduleDto getMonth(int calendarYear, int calendarMonth, Integer academicYear,
                                     Long teacherId, Long groupId) {
        LocalDate from = LocalDate.of(calendarYear, calendarMonth, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        return getRange(from, to, academicYear, teacherId, groupId, true);
    }

    public MonthScheduleDto getRange(LocalDate from, LocalDate to, Integer academicYear,
                                     Long teacherId, Long groupId, boolean withCancelled) {
        int year = academicYear != null ? academicYear : AcademicYearUtil.academicYearOf(from);

        List<Schedule> schedules = scheduleRepository.findByAcademicYear(year).stream()
                .filter(s -> s.getTeacherLoad() != null && s.getTeacherLoad().getGroup() != null
                        && s.getTeacherLoad().getDiscipline() != null)
                .filter(s -> groupId == null || s.getTeacherLoad().getGroup().getId().equals(groupId))
                .toList();

        Map<String, LessonInstance> instanceByKey = new HashMap<>();
        for (LessonInstance li : instanceRepository.findByLessonDateBetween(from, to)) {
            if (li.getSchedule() != null) instanceByKey.putIfAbsent(li.getSchedule().getId() + "_" + li.getLessonDate(), li);
        }

        List<SpecialEvent> events = specialEventRepository.findByAcademicYear(year).stream()
                .filter(e -> e.getGroup() != null)
                .filter(e -> !e.getEndDate().isBefore(from) && !e.getStartDate().isAfter(to))
                .filter(e -> groupId == null || e.getGroup().getId().equals(groupId))
                .toList();

        List<MonthLessonDto> result = new ArrayList<>();
        Set<Long> teacherGroups = new HashSet<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (AcademicYearUtil.isVacation(date)) continue;
            int semester = AcademicYearUtil.semesterOfDate(date);
            int week = AcademicYearUtil.academicWeek(date, year);
            for (Schedule s : schedules) {
                if (!ScheduleOccurrence.occursOn(s, date, week, semester)) continue;
                TeacherLoad load = s.getTeacherLoad();
                if (eventBlocks(events, load, date)) continue;

                LessonInstance instance = instanceByKey.get(s.getId() + "_" + date);
                String status = instance != null ? instance.getStatus().name() : "PLANNED";
                boolean cancelled = instance != null && (instance.getStatus() == LessonInstance.Status.CANCELLED
                        || instance.getStatus() == LessonInstance.Status.INDEPENDENT_WORK);
                if (cancelled && !withCancelled) continue;

                Long originalTeacherId = load.getTeacher() != null ? load.getTeacher().getId() : null;
                String originalTeacherName = load.getTeacher() != null ? load.getTeacher().getFullName() : "Не назначен";
                boolean substituted = instance != null && instance.getStatus() == LessonInstance.Status.REPLACED;
                Long actualTeacherId = instance != null && instance.getActualTeacher() != null
                        ? instance.getActualTeacher().getId() : originalTeacherId;
                String actualTeacherName = instance != null && instance.getActualTeacher() != null
                        ? instance.getActualTeacher().getFullName() : originalTeacherName;

                if (teacherId != null && !teacherId.equals(originalTeacherId) && !teacherId.equals(actualTeacherId)) {
                    continue;
                }
                if (teacherId != null) teacherGroups.add(load.getGroup().getId());

                int pairIdx = GenerationGrid.pairIndex(s.getStartTime());
                result.add(new MonthLessonDto(
                        s.getId(),
                        instance != null ? instance.getId() : null,
                        date,
                        s.getDayOfWeek().toString(),
                        s.getStartTime().toString(),
                        s.getEndTime().toString(),
                        s.getClassroom(),
                        load.getDiscipline().getName(),
                        load.getGroup().getName(),
                        originalTeacherId,
                        originalTeacherName,
                        actualTeacherName,
                        status,
                        substituted,
                        instance != null ? instance.getNote() : null,
                        load.getGroup().getId(),
                        actualTeacherId,
                        pairIdx >= 0 ? pairIdx + 1 : null,
                        load.getLessonType(),
                        s.getRescheduledFromDate()));
            }
        }
        result.sort(Comparator.comparing(MonthLessonDto::date).thenComparing(MonthLessonDto::startTime));

        List<MonthEventDto> eventDtos = events.stream()
                .filter(e -> teacherId == null || teacherGroups.contains(e.getGroup().getId()))
                .map(e -> new MonthEventDto(
                        e.getId(),
                        e.getType().name(),
                        typeLabel(e.getType()),
                        e.getGroup().getId(),
                        e.getGroup().getName(),
                        e.getDiscipline() != null ? e.getDiscipline().getName() : null,
                        e.getStartDate(),
                        e.getEndDate()))
                .toList();

        return new MonthScheduleDto(result, eventDtos);
    }

    private static boolean eventBlocks(List<SpecialEvent> events, TeacherLoad load, LocalDate date) {
        for (SpecialEvent e : events) {
            if (!e.getGroup().getId().equals(load.getGroup().getId())) continue;
            if (date.isBefore(e.getStartDate()) || date.isAfter(e.getEndDate())) continue;
            return true;
        }
        return false;
    }

    public static String typeLabel(SpecialEvent.Type type) {
        return switch (type) {
            case EXAM -> "Экзамен";
            case PRODUCTION_PRACTICE -> "Производственная практика";
            case STUDY_PRACTICE -> "Учебная практика";
            case DRIVING -> "Вождение";
        };
    }
}
