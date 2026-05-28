package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.MonthlyRecord;
import com.karyakina.schedule.repository.MonthlyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyRecordService {

    private final MonthlyRecordRepository repository;

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
}
