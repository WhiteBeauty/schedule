package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.UnresolvedReschedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UnresolvedRescheduleRepository extends JpaRepository<UnresolvedReschedule, Long> {

    List<UnresolvedReschedule> findByAcademicYearOrderByOriginalDateAsc(Integer academicYear);

    void deleteBySpecialEventId(Long specialEventId);
}
