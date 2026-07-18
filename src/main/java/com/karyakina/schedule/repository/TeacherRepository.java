package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.Teacher;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeacherRepository extends JpaRepository<Teacher, Long> {

    @EntityGraph(attributePaths = {"user"})
    List<Teacher> findAll();

    Optional<Teacher> findById(Long id);

    @EntityGraph(attributePaths = {"user"})
    Optional<Teacher> findByUserId(Long userId);

    Optional<Teacher> findByFullNameIgnoreCase(String fullName);

    @EntityGraph(attributePaths = {"loads", "loads.discipline"})
    List<Teacher> findByDepartment(String department);
}
