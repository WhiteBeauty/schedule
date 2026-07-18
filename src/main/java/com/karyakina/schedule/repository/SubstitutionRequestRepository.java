package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.SubstitutionRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubstitutionRequestRepository extends JpaRepository<SubstitutionRequest, Long> {

    @EntityGraph(attributePaths = {"lessonInstance", "lessonInstance.schedule", "lessonInstance.teacherLoad",
            "sickLeave", "originalTeacher", "candidateTeacher"})
    List<SubstitutionRequest> findByCandidateTeacherIdAndStatus(Long candidateTeacherId, SubstitutionRequest.Status status);

    @EntityGraph(attributePaths = {"lessonInstance", "lessonInstance.schedule", "lessonInstance.teacherLoad",
            "sickLeave", "originalTeacher", "candidateTeacher"})
    List<SubstitutionRequest> findByLessonInstanceIdOrderByPriorityRankAsc(Long lessonInstanceId);

    @EntityGraph(attributePaths = {"lessonInstance", "sickLeave", "originalTeacher", "candidateTeacher"})
    List<SubstitutionRequest> findBySickLeaveId(Long sickLeaveId);

    @EntityGraph(attributePaths = {"lessonInstance", "lessonInstance.schedule", "lessonInstance.teacherLoad",
            "sickLeave", "originalTeacher", "candidateTeacher"})
    List<SubstitutionRequest> findAllByOrderByCreatedAtDesc();
}
