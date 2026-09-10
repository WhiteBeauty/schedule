package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.LessonInstance;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LessonInstanceRepository extends JpaRepository<LessonInstance, Long> {

    @EntityGraph(attributePaths = {"schedule", "teacherLoad", "teacherLoad.teacher", "teacherLoad.group",
            "teacherLoad.discipline", "originalTeacher", "actualTeacher"})
    Optional<LessonInstance> findByScheduleIdAndLessonDate(Long scheduleId, LocalDate lessonDate);

    @EntityGraph(attributePaths = {"schedule", "teacherLoad", "teacherLoad.teacher", "teacherLoad.group",
            "teacherLoad.discipline", "originalTeacher", "actualTeacher"})
    List<LessonInstance> findByAcademicYear(Integer academicYear);

    @Query("SELECT li FROM LessonInstance li WHERE li.originalTeacher.id = :teacherId " +
           "AND li.lessonDate BETWEEN :from AND :to")
    @EntityGraph(attributePaths = {"schedule", "teacherLoad", "teacherLoad.group", "teacherLoad.discipline", "originalTeacher", "actualTeacher"})
    List<LessonInstance> findByOriginalTeacherIdAndDateRange(
            @Param("teacherId") Long teacherId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @EntityGraph(attributePaths = {"schedule", "teacherLoad", "teacherLoad.teacher", "teacherLoad.group", "teacherLoad.discipline"})
    List<LessonInstance> findByLessonDate(LocalDate lessonDate);

    @Query("SELECT li FROM LessonInstance li WHERE li.actualTeacher.id = :teacherId " +
           "AND li.lessonDate = :date AND li.status <> 'CANCELLED'")
    List<LessonInstance> findActiveByActualTeacherIdAndDate(@Param("teacherId") Long teacherId, @Param("date") LocalDate date);

    List<LessonInstance> findByScheduleIdIn(List<Long> scheduleIds);

    List<LessonInstance> findByTeacherLoadId(Long teacherLoadId);

    List<LessonInstance> findByOriginalTeacherIdOrActualTeacherId(Long originalTeacherId, Long actualTeacherId);

    /** Занятия, отменённые конкретным SpecialEvent (экзамен/практика/вождение) — для отката при его удалении. */
    List<LessonInstance> findByCancelledBySpecialEventId(Long specialEventId);

    /** Все материализованные занятия в диапазоне дат — используется для месячного календаря. */
    @EntityGraph(attributePaths = {"schedule", "teacherLoad", "teacherLoad.teacher", "teacherLoad.group",
            "teacherLoad.discipline", "originalTeacher", "actualTeacher"})
    List<LessonInstance> findByLessonDateBetween(LocalDate from, LocalDate to);

    /**
     * Замены (status = REPLACED), актуальные "сейчас" — используется для подсветки
     * пары в расписании другим цветом и показа, что преподаватель заменён.
     * Окно [from, to] задаёт, какие даты ещё считаются "актуальными" (недавно
     * прошедшие/предстоящие), чтобы старые замены не подсвечивались вечно.
     */
    @Query("SELECT li FROM LessonInstance li WHERE li.status = :status " +
           "AND li.academicYear = :year AND li.lessonDate BETWEEN :from AND :to")
    @EntityGraph(attributePaths = {"schedule", "originalTeacher", "actualTeacher"})
    List<LessonInstance> findActiveReplacements(
            @Param("status") LessonInstance.Status status,
            @Param("year") Integer year, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
