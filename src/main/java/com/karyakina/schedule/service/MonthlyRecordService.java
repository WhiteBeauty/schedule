package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.MonthlyRecord;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.MonthlyRecordRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class MonthlyRecordService {

    /** Часов за одну пару — используется тем же числом, что и в ScheduleGeneratorService. */
    private static final int HOURS_PER_LESSON = 2;

    private final MonthlyRecordRepository repository;
    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;
    private final LessonInstanceService lessonInstanceService;

    public List<MonthlyRecord> findByLoad(Long loadId) {
        return repository.findByTeacherLoadId(loadId);
    }

    /**
     * Агрегирует записи помесячно (сумма hours), чтобы в UI был один ряд на месяц.
     */
    public List<MonthlyRecord> findAggregatedByLoad(Long loadId) {
        List<MonthlyRecord> all = repository.findByTeacherLoadId(loadId);
        Map<String, MonthlyRecord> byMonth = new HashMap<>();
        for (MonthlyRecord rec : all) {
            String key = rec.getYear() + "-" + rec.getMonth();
            MonthlyRecord agg = byMonth.get(key);
            if (agg == null) {
                agg = MonthlyRecord.builder()
                        .id(rec.getId())
                        .teacherLoad(rec.getTeacherLoad())
                        .month(rec.getMonth())
                        .year(rec.getYear())
                        .hours(rec.getHours() != null ? rec.getHours() : 0)
                        .adjustedHours(rec.getAdjustedHours())
                        .note(rec.getNote())
                        .changedBy(rec.getChangedBy())
                        .changedAt(rec.getChangedAt())
                        .build();
                byMonth.put(key, agg);
            } else {
                agg.setHours((agg.getHours() != null ? agg.getHours() : 0)
                        + (rec.getHours() != null ? rec.getHours() : 0));
                if (rec.getAdjustedHours() != null) {
                    agg.setAdjustedHours(rec.getAdjustedHours());
                }
                if (rec.getNote() != null && !rec.getNote().isBlank()) {
                    agg.setNote(rec.getNote());
                }
                if (rec.getChangedBy() != null) {
                    agg.setChangedBy(rec.getChangedBy());
                }
                if (agg.getId() == null || (agg.getHours() == 0 && rec.getId() != null)) {
                    agg.setId(rec.getId());
                }
            }
        }
        List<MonthlyRecord> result = new ArrayList<>(byMonth.values());
        result.sort((a, b) -> {
            int y = Integer.compare(a.getYear(), b.getYear());
            return y != 0 ? y : Integer.compare(a.getMonth(), b.getMonth());
        });
        return result;
    }

    @Transactional
    public MonthlyRecord adjust(Long recordId, Integer adjustedHours, String note, String changedBy) {
        MonthlyRecord rec = repository.findById(recordId)
                .orElseThrow(() -> new RuntimeException("Record not found"));
        rec.setAdjustedHours(adjustedHours);
        rec.setNote(note);
        rec.setChangedBy(changedBy);
        return repository.save(rec);
    }

    @Transactional
    public void createMonthlyRecordsForLoad(TeacherLoad load) {
        // Создаём записи для всех 12 месяцев, если их ещё нет
        List<MonthlyRecord> existing = repository.findByTeacherLoadId(load.getId());
        if (existing.isEmpty()) {
            for (int month = 1; month <= 12; month++) {
                MonthlyRecord record = MonthlyRecord.builder()
                        .teacherLoad(load)
                        .month(month)
                        .year(load.getAcademicYear())
                        .hours(0)
                        .build();
                repository.save(record);
            }
        }
    }

    @Transactional
    public void initializeMonthlyRecordsForAllLoads() {
        log.info("Initializing monthly records for all teacher loads...");
        List<TeacherLoad> allLoads = loadRepository.findAll();
        int created = 0;
        for (TeacherLoad load : allLoads) {
            List<MonthlyRecord> existing = repository.findByTeacherLoadId(load.getId());
            if (existing.isEmpty()) {
                for (int month = 1; month <= 12; month++) {
                    MonthlyRecord record = MonthlyRecord.builder()
                            .teacherLoad(load)
                            .month(month)
                            .year(load.getAcademicYear())
                            .hours(0)
                            .build();
                    repository.save(record);
                }
                created++;
            }
        }
        log.info("Created monthly records for {} teacher loads", created);
    }

    /**
     * ПОМЕСЯЧНЫЙ УЧЁТ ПО РАСПИСАНИЮ — ПЛАНОВЫЕ часы (не фактические, см. MonthlyRecord.conductedHours).
     *
     * Раньше считало день-недели-вхождения слепо по всем 12 месяцам года, включая каникулы
     * и месяцы ЧУЖОГО семестра (ТЗ п.1 требует хранить расписание строго по семестрам —
     * см. тот же фикс в MonthlyScheduleService/LessonInstanceService.generateInstancesForDate).
     * Теперь идём по реальным датам месяца и учитываем: каникулы (isVacation), семестр самой
     * записи расписания (Schedule.semester — null = старые записи, действуют всегда, ради
     * обратной совместимости) и, если задана конкретная учебная неделя (числитель/
     * знаменатель) — совпадение с ней.
     *
     * adjustedHours (ручная корректировка администратора) НЕ трогается. conductedHours
     * (фактически проведённые часы) тоже не трогается — это отдельное поле, обновляемое
     * только через LessonInstanceService.confirmInstance/cancelInstance.
     */
    @Transactional
    public void recalculateHoursForLoad(Long loadId) {
        TeacherLoad load = loadRepository.findById(loadId).orElse(null);
        if (load == null) return;
        List<Schedule> schedules = scheduleRepository.findByTeacherLoadId(loadId);
        recalculateHoursForLoad(load, schedules);
    }

    @Transactional
    public void recalculateHoursForLoad(TeacherLoad load, List<Schedule> schedulesForLoad) {
        if (load.getAcademicYear() == null) return;

        List<MonthlyRecord> records = repository.findByTeacherLoadId(load.getId());
        if (records.isEmpty()) {
            createMonthlyRecordsForLoad(load);
            records = repository.findByTeacherLoadId(load.getId());
        }
        Map<Integer, MonthlyRecord> byMonth = new HashMap<>();
        for (MonthlyRecord r : records) byMonth.putIfAbsent(r.getMonth(), r);

        int academicYearStart = load.getAcademicYear();
        for (int month = 1; month <= 12; month++) {
            // Учебный год начинается в сентябре: сентябрь-декабрь относятся к
            // academicYearStart, январь-август — к следующему календарному году.
            int calendarYear = month >= 9 ? academicYearStart : academicYearStart + 1;

            int hours = plannedHoursInMonth(schedulesForLoad, calendarYear, month, academicYearStart);

            MonthlyRecord rec = byMonth.get(month);
            if (rec != null) {
                rec.setHours(hours);
                repository.save(rec);
            }
        }
    }

    /** Плановые часы всех пар этой нагрузки в конкретном календарном месяце — по реальным датам, с учётом каникул/семестра/недели. */
    private int plannedHoursInMonth(List<Schedule> schedulesForLoad, int calendarYear, int month, int academicYear) {
        int hours = 0;
        LocalDate d = LocalDate.of(calendarYear, month, 1);
        while (d.getMonthValue() == month) {
            if (!com.karyakina.schedule.util.AcademicYearUtil.isVacation(d)) {
                int dateSemester = com.karyakina.schedule.util.AcademicYearUtil.semesterOfDate(d);
                int week = lessonInstanceService.computeAcademicWeek(d, academicYear);
                for (Schedule s : schedulesForLoad) {
                    if (s.getDayOfWeek() != d.getDayOfWeek()) continue;
                    if (s.getSemester() != null && !s.getSemester().equals(dateSemester)) continue;
                    if (s.getAcademicWeek() != null && !s.getAcademicWeek().equals(week)) continue;
                    hours += HOURS_PER_LESSON;
                }
            }
            d = d.plusDays(1);
        }
        return hours;
    }
}

