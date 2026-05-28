package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.TeacherLoad;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherLoadRepository extends JpaRepository<TeacherLoad, Long> {

    @EntityGraph(attributePaths = {"teacher", "group", "discipline", "controlPoints", "monthlyRecords"})
    List<TeacherLoad> findByAcademicYear(Integer academicYear);

    @EntityGraph(attributePaths = {"teacher", "group", "discipline", "controlPoints", "monthlyRecords"})
    List<TeacherLoad> findByTeacherIdAndAcademicYear(Long teacherId, Integer academicYear);

    @EntityGraph(attributePaths = {"teacher", "group", "discipline", "controlPoints", "monthlyRecords"})
    Optional<TeacherLoad> findById(Long id);
}
