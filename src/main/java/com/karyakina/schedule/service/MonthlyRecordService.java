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
import java.util.List;

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

