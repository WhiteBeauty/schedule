package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.TeacherLoad;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherLoadRepository extends JpaRepository<TeacherLoad, Long> {

    @EntityGraph(attributePaths = {"teacher", "group", "discipline"})
    List<TeacherLoad> findByAcademicYear(Integer academicYear);

    @EntityGraph(attributePaths = {"teacher", "group", "discipline"})
    List<TeacherLoad> findByTeacherIdAndAcademicYear(Long teacherId, Integer academicYear);

    @EntityGraph(attributePaths = {"teacher", "group", "discipline"})
    Optional<TeacherLoad> findById(Long id);

    @Query("SELECT DISTINCT tl FROM TeacherLoad tl " +
           "LEFT JOIN FETCH tl.teacher " +
           "LEFT JOIN FETCH tl.group " +
           "LEFT JOIN FETCH tl.discipline " +
           "LEFT JOIN FETCH tl.controlPoints " +
           "LEFT JOIN FETCH tl.monthlyRecords " +
           "WHERE tl.teacher.id = :teacherId AND tl.academicYear = :year")
    List<TeacherLoad> findByTeacherIdAndYearWithDetails(Long teacherId, Integer year);
}
