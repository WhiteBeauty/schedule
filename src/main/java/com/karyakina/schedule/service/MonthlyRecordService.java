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

import java.time.DayOfWeek;
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
     * ПОМЕСЯЧНЫЙ УЧЁТ ПО РАСПИСАНИЮ.
     *
     * Раньше поле {@link MonthlyRecord#getHours()} всегда оставалось 0 (заглушка,
     * заполнялась только вручную через {@link #adjust}). Теперь при появлении
     * реального расписания (пары в {@link Schedule}) часы за каждый месяц считаются
     * автоматически: пара повторяется еженедельно по дню недели, поэтому часы за
     * месяц = (сколько раз этот день недели встречается в данном календарном месяце)
     * × 2 (часа за пару), просуммировано по всем парам этой нагрузки.
     *
     * adjustedHours (ручная корректировка администратора) НЕ трогается — считается
     * только "плановое по расписанию" значение hours.
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

            int hours = 0;
            for (Schedule s : schedulesForLoad) {
                if (s.getDayOfWeek() == null) continue;
                hours += countWeekdayOccurrencesInMonth(s.getDayOfWeek(), calendarYear, month) * HOURS_PER_LESSON;
            }

            MonthlyRecord rec = byMonth.get(month);
            if (rec != null) {
                rec.setHours(hours);
                repository.save(rec);
            }
        }
    }

    private int countWeekdayOccurrencesInMonth(DayOfWeek dow, int calendarYear, int month) {
        LocalDate d = LocalDate.of(calendarYear, month, 1);
        int count = 0;
        while (d.getMonthValue() == month) {
            if (d.getDayOfWeek() == dow) count++;
            d = d.plusDays(1);
        }
        return count;
    }
}

