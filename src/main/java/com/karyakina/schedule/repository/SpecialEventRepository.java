package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.SpecialEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SpecialEventRepository extends JpaRepository<SpecialEvent, Long> {

    List<SpecialEvent> findByAcademicYear(Integer academicYear);

    List<SpecialEvent> findByGroupIdAndAcademicYear(Long groupId, Integer academicYear);

    List<SpecialEvent> findByGroupIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long groupId, LocalDate onOrAfter, LocalDate onOrBefore);
}
