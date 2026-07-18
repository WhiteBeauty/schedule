package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.MonthlyRecord;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.MonthlyRecordRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class MonthlyRecordService {

    private final MonthlyRecordRepository repository;
    private final TeacherLoadRepository loadRepository;

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
}

