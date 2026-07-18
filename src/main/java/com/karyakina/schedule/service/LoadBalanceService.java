package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сверка плановой и распределённой (по расписанию) нагрузки на неделю и месяц.
 * Цель: разница план − факт(в расписании) = 0.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoadBalanceService {

    public static final int WEEKS_PER_YEAR = 36;
    public static final int ACADEMIC_MONTHS = 9;
    public static final double WEEKS_PER_MONTH = WEEKS_PER_YEAR / (double) ACADEMIC_MONTHS;

    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;

    public List<Map<String, Object>> buildReconciliation(Integer academicYear, Long teacherId) {
        List<TeacherLoad> loads = teacherId != null
                ? loadRepository.findByTeacherIdAndAcademicYear(teacherId, academicYear)
                : loadRepository.findByAcademicYear(academicYear);

        List<Map<String, Object>> result = new ArrayList<>();
        for (TeacherLoad load : loads) {
            result.add(toRow(load));
        }
        return result;
    }

    public Map<String, Object> toRow(TeacherLoad load) {
        int plannedWeek = resolveHoursPerWeek(load);
        int scheduledWeek = computeScheduledHoursPerWeek(load);
        int weekDiff = plannedWeek - scheduledWeek;

        int plannedMonth = (int) Math.round(plannedWeek * WEEKS_PER_MONTH);
        int scheduledMonth = (int) Math.round(scheduledWeek * WEEKS_PER_MONTH);
        int monthDiff = plannedMonth - scheduledMonth;

        int plannedYear = load.getPlannedHours() != null ? load.getPlannedHours() : 0;
        int readYear = load.getReadHours() != null ? load.getReadHours() : 0;
        int yearDiff = plannedYear - readYear;

        Map<String, Object> row = new HashMap<>();
        row.put("loadId", load.getId());
        row.put("teacherId", load.getTeacher().getId());
        row.put("teacherName", load.getTeacher().getFullName());
        row.put("disciplineName", load.getDiscipline().getName());
        row.put("groupName", load.getGroup().getName());
        row.put("hoursPerWeek", plannedWeek);
        row.put("scheduledHoursPerWeek", scheduledWeek);
        row.put("weekDiff", weekDiff);
        row.put("plannedHoursPerMonth", plannedMonth);
        row.put("scheduledHoursPerMonth", scheduledMonth);
        row.put("monthDiff", monthDiff);
        row.put("plannedHours", plannedYear);
        row.put("readHours", readYear);
        row.put("yearDiff", yearDiff);
        row.put("weekBalanced", weekDiff == 0);
        row.put("monthBalanced", monthDiff == 0);
        row.put("yearBalanced", yearDiff == 0);
        return row;
    }

    /** Часов в неделю по плану (явное поле или plannedHours / 36). */
    public int resolveHoursPerWeek(TeacherLoad load) {
        if (load.getHoursPerWeek() != null && load.getHoursPerWeek() > 0) {
            return load.getHoursPerWeek();
        }
        int planned = load.getPlannedHours() != null ? load.getPlannedHours() : 0;
        if (planned <= 0) return 0;
        return Math.max(1, (int) Math.round(planned / (double) WEEKS_PER_YEAR));
    }

    /**
     * Сумма длительностей пар в шаблоне расписания на одну учебную неделю.
     * Пары с academicWeek == null считаются еженедельными;
     * пары с конкретной неделей учитываются пропорционально (1/WEEKS_PER_YEAR),
     * но для сверки «типовой недели» берём только еженедельные + среднюю долю разовых.
     */
    public int computeScheduledHoursPerWeek(TeacherLoad load) {
        List<Schedule> schedules = scheduleRepository.findByTeacherLoadId(load.getId());
        double weekly = 0;
        double oneOffTotal = 0;
        for (Schedule s : schedules) {
            double hours = pairHours(s);
            if (s.getAcademicWeek() == null) {
                weekly += hours;
            } else {
                oneOffTotal += hours;
            }
        }
        // Разовые пары равномерно разносятся по учебному году
        weekly += oneOffTotal / WEEKS_PER_YEAR;
        return (int) Math.round(weekly);
    }

    private double pairHours(Schedule s) {
        long minutes = ChronoUnit.MINUTES.between(s.getStartTime(), s.getEndTime());
        return Math.max(1.0, minutes / 60.0);
    }

    /**
     * После добавления/изменения пары убеждаемся, что hoursPerWeek задан,
     * и возвращаем текущий баланс недели (для UI/валидации).
     */
    @Transactional
    public Map<String, Object> refreshAfterScheduleChange(Long teacherLoadId) {
        TeacherLoad load = loadRepository.findById(teacherLoadId)
                .orElseThrow(() -> new RuntimeException("TeacherLoad not found: " + teacherLoadId));

        if (load.getHoursPerWeek() == null || load.getHoursPerWeek() <= 0) {
            load.setHoursPerWeek(resolveHoursPerWeek(load));
            loadRepository.save(load);
        }
        return toRow(load);
    }
}
