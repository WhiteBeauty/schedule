package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.SickLeave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SickLeaveRepository extends JpaRepository<SickLeave, Long> {

    List<SickLeave> findByTeacherId(Long teacherId);

    List<SickLeave> findByTeacherIdAndAcademicYear(Long teacherId, Integer academicYear);

    @Query("SELECT s FROM SickLeave s WHERE s.teacher.id = :teacherId " +
           "AND s.startDate <= :checkDate AND s.endDate >= :checkDate")
    List<SickLeave> findByTeacherIdAndDateRange(
        @Param("teacherId") Long teacherId,
        @Param("checkDate") LocalDate checkDate
    );
}
