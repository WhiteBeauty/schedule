package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.MonthlyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MonthlyRecordRepository extends JpaRepository<MonthlyRecord, Long> {
    List<MonthlyRecord> findByTeacherLoadId(Long teacherLoadId);
}
