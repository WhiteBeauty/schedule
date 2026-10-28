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
            if (!com.karyakina.schedule.util.ScheduleOccurrence.occursOn(schedule, date, currentWeek, dateSemester)) continue;

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

        if (isCredited(instance)) {
            return instance;
        }
        if (instance.getStatus() == LessonInstance.Status.REPLACED) {
            addHours(instance.getTeacherLoad(), instance.getDurationHours(), instance.getLessonDate(),
                    "Замена от " + instance.getLessonDate(), changedBy);
            instance.setConfirmedAt(LocalDateTime.now());
            return instanceRepository.save(instance);
        }
        if (instance.getStatus() != LessonInstance.Status.PLANNED) {
            return instance;
        }

        addHours(instance.getTeacherLoad(), instance.getDurationHours(), instance.getLessonDate(),
                "Подтверждена пара от " + instance.getLessonDate(), changedBy);

        instance.setStatus(LessonInstance.Status.CONFIRMED);
        instance.setConfirmedAt(LocalDateTime.now());
        return instanceRepository.save(instance);
    }

    public static boolean isCredited(LessonInstance li) {
        return li.getStatus() == LessonInstance.Status.CONFIRMED
                || (li.getStatus() == LessonInstance.Status.REPLACED && li.getConfirmedAt() != null);
    }

    public static boolean hasEnded(LessonInstance li, LocalDateTime now) {
        LocalDate date = li.getLessonDate();
        if (date.isBefore(now.toLocalDate())) return true;
        if (date.isAfter(now.toLocalDate())) return false;
        return li.getSchedule() != null && li.getSchedule().getEndTime() != null
                && !li.getSchedule().getEndTime().isAfter(now.toLocalTime());
    }

    @Transactional
    public LessonInstance cancelInstance(Long instanceId, String note, String changedBy) {
        LessonInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("Занятие не найдено: " + instanceId));

        if (isCredited(instance)) {
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

        if (isCredited(instance)) {
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

        if (isCredited(instance)) {
            subtractHours(instance.getTeacherLoad(), instance.getDurationHours(), instance.getLessonDate(),
                    "Пара передана другому преподавателю (замена)", changedBy);
        }

        TeacherLoad originalLoad = instance.getSchedule().getTeacherLoad();
        TeacherLoad substituteLoad = findOrCreateSubstituteLoad(substitute, originalLoad);

        instance.setTeacherLoad(substituteLoad);
        instance.setActualTeacher(substitute);
        instance.setStatus(LessonInstance.Status.REPLACED);
        instance.setNote(note);
        instance.setConfirmedAt(null);
        // заменяющему часы только когда пара прошла
        if (hasEnded(instance, LocalDateTime.now())) {
            addHours(substituteLoad, instance.getDurationHours(), instance.getLessonDate(),
                    "Замена вместо " + instance.getOriginalTeacher().getFullName()
                            + " от " + instance.getLessonDate(), changedBy);
            instance.setConfirmedAt(LocalDateTime.now());
        }
        return instanceRepository.save(instance);
    }

    @Transactional
    public TeacherLoad findOrCreateSubstituteLoad(Teacher substitute, TeacherLoad originalLoad) {
        if (originalLoad.getTeacher() != null && originalLoad.getTeacher().getId().equals(substitute.getId())) {
            return originalLoad;
        }
        List<TeacherLoad> substituteLoads = loadRepository.findByTeacherIdAndAcademicYear(
                substitute.getId(), originalLoad.getAcademicYear());

        return substituteLoads.stream()
                .filter(l -> Boolean.TRUE.equals(l.getOverload())
                        && l.getDiscipline().getId().equals(originalLoad.getDiscipline().getId())
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

    @Transactional
    public void revertHoursBeforeDelete(LessonInstance instance, String reason) {
        if (instance.getTeacherLoad() == null || instance.getDurationHours() == null) return;
        if (isCredited(instance)) {
            subtractHours(instance.getTeacherLoad(), instance.getDurationHours(), instance.getLessonDate(),
                    reason, "system:delete");
        }
    }

    @Transactional
    public LessonInstance revertReplacement(Long instanceId, String note) {
        LessonInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("Занятие не найдено: " + instanceId));
        if (instance.getStatus() != LessonInstance.Status.REPLACED) return instance;
        if (isCredited(instance)) {
            subtractHours(instance.getTeacherLoad(), instance.getDurationHours(), instance.getLessonDate(),
                    note, "system:revert");
        }
        instance.setTeacherLoad(instance.getSchedule().getTeacherLoad());
        instance.setActualTeacher(instance.getOriginalTeacher());
        instance.setStatus(LessonInstance.Status.PLANNED);
        instance.setConfirmedAt(null);
        instance.setNote(null);
        return instanceRepository.save(instance);
    }

    public boolean isTeacherSickOnDate(Long teacherId, LocalDate date) {
        return !sickLeaveRepository.findByTeacherIdAndDateRange(teacherId, date).isEmpty();
    }

    @Transactional
    public void correctReadHours(TeacherLoad load, int newReadHours, String changedBy) {
        int delta = newReadHours - (load.getReadHours() == null ? 0 : load.getReadHours());
        if (delta == 0) return;
        load.setReadHours(Math.max(0, newReadHours));
        loadRepository.save(load);
        upsertMonthly(load, LocalDate.now().getMonthValue(), delta, "Ручная корректировка вычитанных часов", changedBy);
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
        return com.karyakina.schedule.util.AcademicYearUtil.academicWeek(date, academicYear);
    }
}
