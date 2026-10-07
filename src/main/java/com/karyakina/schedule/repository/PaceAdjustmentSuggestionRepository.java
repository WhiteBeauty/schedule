package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.PaceAdjustmentSuggestion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaceAdjustmentSuggestionRepository extends JpaRepository<PaceAdjustmentSuggestion, Long> {

    @EntityGraph(attributePaths = {"teacherLoad", "teacherLoad.teacher", "teacherLoad.group", "teacherLoad.discipline"})
    Optional<PaceAdjustmentSuggestion> findById(Long id);

    boolean existsByTeacherLoadIdAndAcademicYearAndSemesterAndStatus(
            Long teacherLoadId, Integer academicYear, Integer semester, PaceAdjustmentSuggestion.Status status);

    @EntityGraph(attributePaths = {"teacherLoad", "teacherLoad.teacher", "teacherLoad.group", "teacherLoad.discipline"})
    List<PaceAdjustmentSuggestion> findByStatusOrderByCreatedAtDesc(PaceAdjustmentSuggestion.Status status);
}
