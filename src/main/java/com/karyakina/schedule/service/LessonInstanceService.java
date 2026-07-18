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

/**
 * МОДУЛЬ ТАРИФИКАЦИИ И УЧЁТА НАГРУЗКИ (ядро системы).
 *
 * {@link LessonInstance} — конкретное занятие на конкретную дату, порождённое шаблоном
 * {@link Schedule}. Именно операции над LessonInstance автоматически пересчитывают
 * фактическую нагрузку (readHours) преподавателя:
 *  - confirmInstance(...)  — пара проведена -> часы ПРИБАВЛЯЮТСЯ;
 *  - cancelInstance(...)   — пара отменена -> ранее начисленные часы ВЫЧИТАЮТСЯ;
 *  - replaceInstance(...)  — пара передана другому преподавателю -> часы переносятся:
 *        вычитаются у исходного (если были начислены) и прибавляются заменяющему.
 * Если у заменяющего преподавателя нет собственного резерва часов по этой
 * дисциплине/группе — создаётся запись TeacherLoad с флагом overload=true
 * ("переработка"), как того требует ТЗ.
 */
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

    /**
     * Находит (или создаёт как PLANNED) конкретные занятия на указанную дату для всех
     * шаблонов расписания, действующих в этот день недели / учебную неделю.
     * Идемпотентно: повторный вызов на ту же дату не создаёт дублей (уникальный индекс
     * schedule_id+lesson_date).
     */
    @Transactional
    public List<LessonInstance> generateInstancesForDate(LocalDate date, Integer academicYear) {
        List<Schedule> schedules = scheduleRepository.findByAcademicYear(academicYear);
        int currentWeek = computeAcademicWeek(date, academicYear);

        for (Schedule schedule : schedules) {
            if (schedule.getDayOfWeek() != date.getDayOfWeek()) continue;
            if (schedule.getAcademicWeek() != null && !schedule.getAcademicWeek().equals(currentWeek)) continue;

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

    /** Пара проведена — начисляем часы преподавателю, указанному в teacherLoad занятия. */
    @Transactional
    public LessonInstance confirmInstance(Long instanceId, String changedBy) {
        LessonInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new RuntimeException("Занятие не найдено: " + instanceId));

        if (instance.getStatus() == LessonInstance.Status.CONFIRMED
                || instance.getStatus() == LessonInstance.Status.REPLACED) {
            return instance; // уже учтено, повторно не начисляем
        }

        addHours(instance.getTeacherLoad(), instance.getDurationHours(), instance.getLessonDate(),
                "Подтверждена пара от " + instance.getLessonDate(), changedBy);

        instance.setStatus(LessonInstance.Status.CONFIRMED);
        instance.setConfirmedAt(LocalDateTime.now());
        return instanceRepository.save(instance);
    }

    /** Пара отменена. Если часы уже были начислены (CONFIRMED/REPLACED) — вычитаем их обратно. */
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

    /**
     * Замена преподавателя на конкретном занятии (форс-мажор). Часы автоматически
     * переносятся: списываются у исходного (если были начислены) и начисляются
     * заменяющему преподавателю. Если у заменяющего нет собственной плановой нагрузки
     * по этой дисциплине/группе, создаётся новая запись TeacherLoad с overload=true.
     */
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

    /** Находит нагрузку заменяющего преподавателя по той же дисциплине/группе/году, либо создаёт "переработку". */
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

    // ==================== Внутренняя бухгалтерия часов ====================

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

    /** Обновляет (или создаёт) одну помесячную запись вместо фрагментарных дельт. */
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
                    .hours(deltaHours)
                    .note(note)
                    .changedBy(changedBy)
                    .build());
        } else {
            base.setHours((base.getHours() != null ? base.getHours() : 0) + deltaHours);
            if (note != null && !note.isBlank()) {
                base.setNote(note);
            }
            if (changedBy != null) {
                base.setChangedBy(changedBy);
            }
            monthlyRecordRepository.save(base);
        }
    }

    /**
     * Приблизительный номер учебной недели (1..~40) для сопоставления с Schedule.academicWeek.
     * Отсчёт ведётся от 1 сентября учебного года.
     */
    public int computeAcademicWeek(LocalDate date, Integer academicYear) {
        LocalDate start = LocalDate.of(academicYear, 9, 1);
        if (date.isBefore(start)) {
            start = LocalDate.of(academicYear - 1, 9, 1);
        }
        long weeks = ChronoUnit.WEEKS.between(
                start.with(WeekFields.of(Locale.getDefault()).getFirstDayOfWeek()),
                date.with(WeekFields.of(Locale.getDefault()).getFirstDayOfWeek()));
        return (int) weeks + 1;
    }
}
