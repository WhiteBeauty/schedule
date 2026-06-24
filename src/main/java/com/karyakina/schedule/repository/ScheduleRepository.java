package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.Schedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    @EntityGraph(attributePaths = {"teacherLoad", "teacherLoad.teacher", "teacherLoad.group", "teacherLoad.discipline"})
    List<Schedule> findByAcademicYear(Integer academicYear);

    @EntityGraph(attributePaths = {"teacherLoad", "teacherLoad.teacher", "teacherLoad.group", "teacherLoad.discipline"})
    List<Schedule> findByTeacherLoadId(Long teacherLoadId);

    @Query("SELECT s FROM Schedule s JOIN s.teacherLoad tl WHERE tl.teacher.id = :teacherId AND s.academicYear = :academicYear")
    @EntityGraph(attributePaths = {"teacherLoad", "teacherLoad.teacher", "teacherLoad.group", "teacherLoad.discipline"})
    List<Schedule> findByTeacherLoadTeacherIdAndAcademicYear(
        @Param("teacherId") Long teacherId,
        @Param("academicYear") Integer academicYear
    );
}
