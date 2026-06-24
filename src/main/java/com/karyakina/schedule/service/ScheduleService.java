package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.SickLeave;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.domain.MonthlyRecord;
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
import java.time.temporal.ChronoUnit;
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
        // Получить все TeacherLoad для преподавателя (текущий год), затем найти все Schedule для них
        Integer currentYear = java.time.Year.now().getValue();
        List<Schedule> result = new java.util.ArrayList<>();
        loadRepository.findByTeacherIdAndAcademicYear(teacherId, currentYear).forEach(load -> {
            result.addAll(scheduleRepository.findByTeacherLoadId(load.getId()));
        });
        return result;
    }

    @Transactional
    public Schedule createSchedule(Long teacherLoadId, DayOfWeek dayOfWeek, LocalTime startTime, 
                                   LocalTime endTime, String classroom, Integer academicWeek, Integer academicYear) {
        TeacherLoad load = loadRepository.findById(teacherLoadId)
                .orElseThrow(() -> new RuntimeException("TeacherLoad not found: " + teacherLoadId));
        
        Schedule schedule = Schedule.builder()
                .teacherLoad(load)
                .dayOfWeek(dayOfWeek)
                .startTime(startTime)
                .endTime(endTime)
                .classroom(classroom)
                .academicWeek(academicWeek)
                .academicYear(academicYear)
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
        List<Schedule> schedules = scheduleRepository.findByAcademicYear(academicYear);
        
        for (Schedule schedule : schedules) {
            TeacherLoad load = schedule.getTeacherLoad();
            Long teacherId = load.getTeacher().getId();
            
            // Проверяем, не болеет ли преподаватель
            if (isTeacherSickOnDate(teacherId, today)) {
                continue; // Не вычитаем часы если преподаватель болеет
            }
            
            // Проверяем, была ли пара сегодня
            if (schedule.getDayOfWeek() == today.getDayOfWeek()) {
                // Вычисляем длительность пары в часах
                long durationMinutes = ChronoUnit.MINUTES.between(schedule.getStartTime(), schedule.getEndTime());
                double hours = durationMinutes / 60.0;
                
                // Обновляем readHours
                load.setReadHours(load.getReadHours() + (int) Math.round(hours));
                loadRepository.save(load);
                
                // Создаём запись в monthly_records
                int month = today.getMonthValue();
                MonthlyRecord record = MonthlyRecord.builder()
                        .teacherLoad(load)
                        .month(month)
                        .year(academicYear)
                        .hours((int) Math.round(hours))
                        .build();
                recordRepository.save(record);
            }
        }
    }

    /**
     * Автоматический вычет часов каждый день в 23:00
     */
    @Scheduled(cron = "0 0 23 * * *")
    @Transactional
    public void scheduledAutoDeduct() {
        log.info("Running scheduled auto-deduct hours...");
        try {
            Integer currentYear = LocalDate.now().getYear();
            // Определяем учебный год: если сейчас январь-май, то учебный год начался в прошлом году
            int academicYear = LocalDate.now().getMonthValue() >= 9 ? currentYear : currentYear - 1;
            autoDeductHours(academicYear);
            log.info("Auto-deduct completed successfully");
        } catch (Exception e) {
            log.error("Error during auto-deduct: {}", e.getMessage(), e);
        }
    }
}
