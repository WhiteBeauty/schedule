package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.Tarification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TarificationRepository extends JpaRepository<Tarification, Long> {

    List<Tarification> findAllByOrderByImportedAtDesc();

    List<Tarification> findByAcademicYearOrderByImportedAtDesc(Integer academicYear);
}
