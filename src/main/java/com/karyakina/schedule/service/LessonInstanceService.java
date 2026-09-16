package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class LessonInstanceService {

    private final LessonInstanceRepository instanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final TeacherLoadRepository loadRepository;
    private final MonthlyRecordRepository monthlyRecordRepository;
    private final SickLeaveRepository sickLeaveRepository;

    public List<LessonInstance> findByDate(LocalDate date) {
        return instanceRepository.findByLessonDate(date);
    }

    public List<LessonInstance> findByTeacherAndRange(Long teacherId, LocalDate from, LocalDate to) {
        return instanceRepository.findByOriginalTeacherIdAndDateRange(teacherId, from, to);
    }

    @Transactional
    public List<LessonInstance> generateInstancesForDate(LocalDate date, Integer academicYear) {
        if (com.karyakina.schedule.util.AcademicYearUtil.isVacation(date)) {
            return instanceRepository.findByLessonDate(date);
        }
        int dateSemester = com.karyakina.schedule.util.AcademicYearUtil.semesterOfDate(date);

        List<Schedule> schedules = scheduleRepository.findByAcademicYear(academicYear);
        int currentWeek = computeAcademicWeek(date, academicYear);

        for (Schedule schedule : schedules) {
            if (schedule.getDayOfWeek() != date.getDayOfWeek()) continue;
            if (schedule.getAcademicWeek() != null && !schedule.getAcademicWeek().equals(currentWeek)) continue;
            if (schedule.getSemester() != null && !schedule.getSemester().equals(dateSemester)) continue;

            instanceRepository.findByScheduleIdAndLessonDate(schedule.getId(), date)
                    .orElseGet(() -> {
                        long minutes = ChronoUnit.MINUTES.between(schedule.getStartTime(), schedule.getEndTime());
                        int hours = (int) Math.max(1, Math.round(minutes / 60.0));
                        LessonInstance instance = LessonInstance.builder()
                                .schedule(schedule)
                                .lessonDate(date)
                                .academicYear(academicYear)
                                .durationHours(hours)
                                .status(LessonInstance.Status.PLANNED)
                                .teacherLoad(schedule.getTeacherLoad())
                                .originalTeacher(schedule.getTeacherLoad().getTeacher())
                                .actualTeacher(schedule.getTeacherLoad().getTeacher())
                                .build();
                        return instanceRepository.save(instance);
                    });
        }
        return instanceRepository.findByLessonDate(date);
    }

    @Transactional
    public LessonInstance confirmInstance(Long instanceId, String changedBy) {
        LessonInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("Занятие не найдено: " + instanceId));

        if (instance.getStatus() == LessonInstance.Status.CONFIRMED
                || instance.getStatus() == LessonInstance.Status.REPLACED) {
            return instance;
        }

        addHours(instance.getTeacherLoad(), instance.getDurationHours(), instance.getLessonDate(),
                "Подтверждена пара от " + instance.getLessonDate(), changedBy);

        instance.setStatus(LessonInstance.Status.CONFIRMED);
        instance.setConfirmedAt(LocalDateTime.now());
        return instanceRepository.save(instance);
    }

    @Transactional
    public LessonInstance cancelInstance(Long instanceId, String note, String changedBy) {
        LessonInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("Занятие не найдено: " + instanceId));

        if (instance.getStatus() == LessonInstance.Status.CONFIRMED
                || instance.getStatus() == LessonInstance.Status.REPLACED) {
            subtractHours(instance.getTeacherLoad(), instance.getDurationHours(), instance.getLessonDate(),
                    "Отмена пары от " + instance.getLessonDate() + (note != null ? ": " + note : ""), changedBy);
        }

        instance.setStatus(LessonInstance.Status.CANCELLED);
        instance.setCancelledAt(LocalDateTime.now());
        instance.setNote(note);
        return instanceRepository.save(instance);
    }

    @Transactional
    public LessonInstance markIndependentWork(Long instanceId, String note, String changedBy) {
        LessonInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("Занятие не найдено: " + instanceId));

        if (instance.getStatus() == LessonInstance.Status.CONFIRMED
                || instance.getStatus() == LessonInstance.Status.REPLACED) {
            subtractHours(instance.getTeacherLoad(), instance.getDurationHours(), instance.getLessonDate(),
                    "Переведено в самостоятельную работу от " + instance.getLessonDate()
                            + (note != null ? ": " + note : ""), changedBy);
        }

        instance.setStatus(LessonInstance.Status.INDEPENDENT_WORK);
        instance.setCancelledAt(LocalDateTime.now());
        instance.setNote(note != null ? note : "Самостоятельная работа");
        return instanceRepository.save(instance);
    }

    @Transactional
    public LessonInstance replaceInstance(Long instanceId, Teacher substitute, String note, String changedBy) {
        LessonInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("Занятие не найдено: " + instanceId));

        if (instance.getStatus() == LessonInstance.Status.CONFIRMED) {
            subtractHours(instance.getTeacherLoad(), instance.getDurationHours(), instance.getLessonDate(),
                    "Пара передана другому преподавателю (замена)", changedBy);
        }

        TeacherLoad originalLoad = instance.getSchedule().getTeacherLoad();
        TeacherLoad substituteLoad = findOrCreateSubstituteLoad(substitute, originalLoad);

        addHours(substituteLoad, instance.getDurationHours(), instance.getLessonDate(),
                "Замена вместо " + instance.getOriginalTeacher().getFullName()
                        + " от " + instance.getLessonDate(), changedBy);

        instance.setTeacherLoad(substituteLoad);
        instance.setActualTeacher(substitute);
        instance.setStatus(LessonInstance.Status.REPLACED);
        instance.setConfirmedAt(LocalDateTime.now());
        instance.setNote(note);
        return instanceRepository.save(instance);
    }

    @Transactional
    public TeacherLoad findOrCreateSubstituteLoad(Teacher substitute, TeacherLoad originalLoad) {
        List<TeacherLoad> substituteLoads = loadRepository.findByTeacherIdAndAcademicYear(
                substitute.getId(), originalLoad.getAcademicYear());

        return substituteLoads.stream()
                .filter(l -> l.getDiscipline().getId().equals(originalLoad.getDiscipline().getId())
                        && l.getGroup().getId().equals(originalLoad.getGroup().getId()))
                .findFirst()
                .orElseGet(() -> {
                    TeacherLoad overloadLoad = TeacherLoad.builder()
                            .teacher(substitute)
                            .group(originalLoad.getGroup())
                            .discipline(originalLoad.getDiscipline())
                            .plannedHours(0)
                            .firstSemesterHours(0)
                            .secondSemesterHours(0)
                            .readHours(0)
                            .academicYear(originalLoad.getAcademicYear())
                            .overload(true)
                            .build();
                    return loadRepository.save(overloadLoad);
                });
    }

    public boolean isTeacherSickOnDate(Long teacherId, LocalDate date) {
        return !sickLeaveRepository.findByTeacherIdAndDateRange(teacherId, date).isEmpty();
    }

    private void addHours(TeacherLoad load, int hours, LocalDate date, String note, String changedBy) {
        load.setReadHours(load.getReadHours() + hours);
        loadRepository.save(load);
        upsertMonthly(load, date.getMonthValue(), hours, note, changedBy);
    }

    private void subtractHours(TeacherLoad load, int hours, LocalDate date, String note, String changedBy) {
        load.setReadHours(Math.max(0, load.getReadHours() - hours));
        loadRepository.save(load);
        upsertMonthly(load, date.getMonthValue(), -hours, note, changedBy);
    }

    private void upsertMonthly(TeacherLoad load, int month, int deltaHours, String note, String changedBy) {
        List<MonthlyRecord> existing = monthlyRecordRepository.findByTeacherLoadId(load.getId());
        MonthlyRecord base = existing.stream()
                .filter(r -> r.getMonth().equals(month) && r.getYear().equals(load.getAcademicYear()))
                .findFirst()
                .orElse(null);

        if (base == null) {
            monthlyRecordRepository.save(MonthlyRecord.builder()
                    .teacherLoad(load)
                    .month(month)
                    .year(load.getAcademicYear())
                    .hours(0)
                    .conductedHours(Math.max(0, deltaHours))
                    .note(note)
                    .changedBy(changedBy)
                    .build());
        } else {
            int current = base.getConductedHours() != null ? base.getConductedHours() : 0;
            base.setConductedHours(Math.max(0, current + deltaHours));
            if (note != null && !note.isBlank()) {
                base.setNote(note);
            }
            if (changedBy != null) {
                base.setChangedBy(changedBy);
            }
            monthlyRecordRepository.save(base);
        }
    }

    public int computeAcademicWeek(LocalDate date, Integer academicYear) {
        LocalDate start = LocalDate.of(academicYear, 9, 1);
        if (date.isBefore(start)) {
            start = LocalDate.of(academicYear - 1, 9, 1);
        }
        WeekFields iso = WeekFields.ISO;
        long weeks = ChronoUnit.WEEKS.between(
                start.with(iso.getFirstDayOfWeek()),
                date.with(iso.getFirstDayOfWeek()));
        return (int) weeks + 1;
    }
}
