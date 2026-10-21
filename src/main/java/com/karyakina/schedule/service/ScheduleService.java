package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.SickLeave;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.SickLeaveRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.repository.MonthlyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final SickLeaveRepository sickLeaveRepository;
    private final TeacherLoadRepository loadRepository;
    private final MonthlyRecordRepository recordRepository;
    private final LessonInstanceService lessonInstanceService;

    public List<Schedule> findByYear(Integer year) {
        return scheduleRepository.findByAcademicYear(year);
    }

    public List<Schedule> findByTeacherIdAndYear(Long teacherId, Integer year) {
        try {
            List<Schedule> schedules = scheduleRepository.findByTeacherLoadTeacherIdAndAcademicYear(teacherId, year);
            System.out.println("Found " + (schedules != null ? schedules.size() : 0) + " schedules for teacher " + teacherId + " year " + year);
            return schedules != null ? schedules : new ArrayList<>();
        } catch (Exception e) {
            System.err.println("Error finding schedules for teacher " + teacherId + ": " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<Schedule> findByTeacherId(Long teacherId) {
        Integer currentYear = com.karyakina.schedule.util.AcademicYearUtil.getCurrentAcademicYearStart();
        List<Schedule> result = new java.util.ArrayList<>();
        loadRepository.findByTeacherIdAndAcademicYear(teacherId, currentYear).forEach(load -> {
            result.addAll(scheduleRepository.findByTeacherLoadId(load.getId()));
        });
        return result;
    }

    private static final int GROUP_MAX_WEEKLY_PAIRS = 18;

    private static boolean sameSemester(Schedule s, Integer semester) {
        return semester == null || s.getSemester() == null || s.getSemester().equals(semester);
    }

    private void validateGroupWeeklyLimit(Long groupId, Integer academicWeek, Integer academicYear,
                                          Integer semester, Long excludeScheduleId) {
        // Раньше считались пары обоих семестров сразу — при готовых осени и весне (по 18 пар)
        // добавить пару вручную было невозможно: «уже 36 пар».
        long count = scheduleRepository.findByAcademicYear(academicYear).stream()
                .filter(s -> s.getTeacherLoad() != null && s.getTeacherLoad().getGroup() != null
                        && s.getTeacherLoad().getGroup().getId().equals(groupId))
                .filter(s -> excludeScheduleId == null || !excludeScheduleId.equals(s.getId()))
                .filter(s -> sameSemester(s, semester))
                .filter(s -> academicWeek == null ? s.getAcademicWeek() == null
                        : (s.getAcademicWeek() == null || academicWeek.equals(s.getAcademicWeek())))
                .filter(s -> academicWeek == null || ((s.getActiveFromWeek() == null || s.getActiveFromWeek() <= academicWeek)
                        && (s.getActiveUntilWeek() == null || s.getActiveUntilWeek() >= academicWeek)))
                .count();
        if (count >= GROUP_MAX_WEEKLY_PAIRS) {
            throw new IllegalStateException("У группы уже " + GROUP_MAX_WEEKLY_PAIRS
                    + " пар в неделю — это максимум по правилам, добавить ещё одну нельзя.");
        }
    }

    private void validateNoConflict(Long teacherLoadId, DayOfWeek dayOfWeek, LocalTime startTime,
                                    String classroom, Integer academicWeek, Integer academicYear,
                                    Integer semester, Long excludeScheduleId) {
        TeacherLoad load = loadRepository.findById(teacherLoadId)
                .orElseThrow(() -> new RuntimeException("TeacherLoad not found: " + teacherLoadId));
        Long teacherId = load.getTeacher() != null ? load.getTeacher().getId() : null;
        Long groupId = load.getGroup() != null ? load.getGroup().getId() : null;

        List<Schedule> sameSlot = scheduleRepository.findByAcademicYear(academicYear).stream()
                .filter(s -> excludeScheduleId == null || !excludeScheduleId.equals(s.getId()))
                .filter(s -> s.getDayOfWeek() == dayOfWeek && s.getStartTime().equals(startTime))
                .filter(s -> sameSemester(s, semester))
                .filter(s -> s.getAcademicWeek() == null || academicWeek == null
                        || academicWeek.equals(s.getAcademicWeek()))
                .toList();

        for (Schedule other : sameSlot) {
            if (teacherId != null && other.getTeacherLoad() != null && other.getTeacherLoad().getTeacher() != null
                    && teacherId.equals(other.getTeacherLoad().getTeacher().getId())) {
                throw new IllegalStateException("Преподаватель " + other.getTeacherLoad().getTeacher().getFullName()
                        + " уже ведёт другую пару в это время (" + dayLabel(dayOfWeek) + ", " + startTime + ").");
            }
            if (groupId != null && other.getTeacherLoad() != null && other.getTeacherLoad().getGroup() != null
                    && groupId.equals(other.getTeacherLoad().getGroup().getId())) {
                throw new IllegalStateException("У группы " + other.getTeacherLoad().getGroup().getName()
                        + " уже есть другая пара в это время (" + dayLabel(dayOfWeek) + ", " + startTime + ").");
            }
            if (classroom != null && !classroom.isBlank() && classroom.equals(other.getClassroom())) {
                throw new IllegalStateException("Аудитория " + classroom
                        + " уже занята в это время (" + dayLabel(dayOfWeek) + ", " + startTime + ").");
            }
        }
    }

    private String dayLabel(DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "понедельник"; case TUESDAY -> "вторник"; case WEDNESDAY -> "среда";
            case THURSDAY -> "четверг"; case FRIDAY -> "пятница"; case SATURDAY -> "суббота";
            case SUNDAY -> "воскресенье";
        };
    }

    @Transactional
    public Schedule createSchedule(Long teacherLoadId, DayOfWeek dayOfWeek, LocalTime startTime,
                                   LocalTime endTime, String classroom, Integer academicWeek, Integer academicYear) {
        return createSchedule(teacherLoadId, dayOfWeek, startTime, endTime, classroom, academicWeek, academicYear, null);
    }

    /** Семестр по номеру учебной недели (неделя 1 — неделя 1 сентября). */
    public static int semesterOfWeek(int academicWeek, int academicYear) {
        LocalDate monday = LocalDate.of(academicYear, 9, 1).with(DayOfWeek.MONDAY).plusWeeks(academicWeek - 1L);
        int sem = com.karyakina.schedule.util.AcademicYearUtil.semesterOfDate(monday.plusDays(4));
        if (sem == 0) sem = com.karyakina.schedule.util.AcademicYearUtil.semesterOfDate(monday.plusDays(14));
        return sem == 0 ? 2 : sem;
    }

    @Transactional
    public Schedule createSchedule(Long teacherLoadId, DayOfWeek dayOfWeek, LocalTime startTime,
                                   LocalTime endTime, String classroom, Integer academicWeek, Integer academicYear,
                                   Integer semester) {
        TeacherLoad load = loadRepository.findById(teacherLoadId)
                .orElseThrow(() -> new RuntimeException("TeacherLoad not found: " + teacherLoadId));

        // Пара без семестра раньше появлялась и осенью, и весной. Теперь семестр определяется явно:
        // из запроса, по номеру недели для разовой пары или текущий/ближайший семестр.
        if (semester == null) {
            semester = academicWeek != null ? semesterOfWeek(academicWeek, academicYear)
                    : com.karyakina.schedule.util.AcademicYearUtil.getCurrentSemester();
        }

        validateGroupWeeklyLimit(load.getGroup().getId(), academicWeek, academicYear, semester, null);
        validateNoConflict(teacherLoadId, dayOfWeek, startTime, classroom, academicWeek, academicYear, semester, null);

        if (load.getHoursPerWeek() == null || load.getHoursPerWeek() <= 0) {
            int planned = load.getPlannedHours() != null ? load.getPlannedHours() : 0;
            int weekly = planned > 0
                    ? Math.max(1, (int) Math.round(planned / (double) LoadBalanceService.WEEKS_PER_YEAR))
                    : 0;
            load.setHoursPerWeek(weekly);
            loadRepository.save(load);
        }

        Schedule schedule = Schedule.builder()
                .teacherLoad(load)
                .dayOfWeek(dayOfWeek)
                .startTime(startTime)
                .endTime(endTime)
                .classroom(classroom)
                .academicWeek(academicWeek)
                .academicYear(academicYear)
                .semester(semester)
                .build();

        return scheduleRepository.save(schedule);
    }

    @Transactional
    public void deleteSchedule(Long id) {
        scheduleRepository.deleteById(id);
    }

    @Transactional
    public Schedule updateSchedule(Long id, DayOfWeek dayOfWeek, LocalTime startTime,
                                   LocalTime endTime, String classroom, Integer academicWeek) {
        Schedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule not found: " + id));

        validateGroupWeeklyLimit(schedule.getTeacherLoad().getGroup().getId(), academicWeek,
                schedule.getAcademicYear(), schedule.getSemester(), schedule.getId());
        validateNoConflict(schedule.getTeacherLoad().getId(), dayOfWeek, startTime, classroom,
                academicWeek, schedule.getAcademicYear(), schedule.getSemester(), schedule.getId());

        schedule.setDayOfWeek(dayOfWeek);
        schedule.setStartTime(startTime);
        schedule.setEndTime(endTime);
        schedule.setClassroom(classroom);
        schedule.setAcademicWeek(academicWeek);

        return scheduleRepository.save(schedule);
    }

    public boolean isTeacherSickOnDate(Long teacherId, LocalDate date) {
        List<SickLeave> sickLeaves = sickLeaveRepository.findByTeacherIdAndDateRange(teacherId, date);
        return !sickLeaves.isEmpty();
    }

    @Transactional
    public void autoDeductHours(Integer academicYear) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        int semester = com.karyakina.schedule.util.AcademicYearUtil.getCurrentSemester();
        LocalDate semesterStart = com.karyakina.schedule.util.AcademicYearUtil.semesterStart(semester, academicYear);
        LocalDate from = semesterStart.isAfter(today) ? today : semesterStart;

        int confirmed = 0;
        int cancelledSick = 0;
        for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) {
            List<LessonInstance> instances = lessonInstanceService.generateInstancesForDate(date, academicYear);
            boolean isToday = date.isEqual(today);

            for (LessonInstance instance : instances) {
                if (instance.getStatus() != LessonInstance.Status.PLANNED) {
                    continue;
                }
                if (instance.getSchedule() == null || instance.getSchedule().getEndTime() == null) {
                    continue;
                }
                if (isToday && now.isBefore(instance.getSchedule().getEndTime())) {
                    continue;
                }
                Long teacherId = instance.getOriginalTeacher().getId();
                if (lessonInstanceService.isTeacherSickOnDate(teacherId, date)) {
                    lessonInstanceService.cancelInstance(instance.getId(),
                            "Преподаватель на больничном, замена не была назначена до конца дня",
                            "system:auto-deduct");
                    cancelledSick++;
                    continue;
                }
                lessonInstanceService.confirmInstance(instance.getId(), "system:auto-deduct");
                confirmed++;
            }
        }
        if (confirmed > 0 || cancelledSick > 0) {
            log.info("Автосписание (год {}, семестр {}, с {} по {}): вычитано пар {}, отменено из-за больничного {}",
                    academicYear, semester, from, today, confirmed, cancelledSick);
        }
    }

    @Scheduled(cron = "0 */15 6-23 * * *")
    @Transactional
    public void scheduledAutoDeduct() {
        log.info("Running scheduled auto-deduct hours...");
        try {
            autoDeductHours(com.karyakina.schedule.util.AcademicYearUtil.getCurrentAcademicYearStart());
            log.info("Auto-deduct completed successfully");
        } catch (Exception e) {
            log.error("Error during auto-deduct: {}", e.getMessage(), e);
        }
    }
}
