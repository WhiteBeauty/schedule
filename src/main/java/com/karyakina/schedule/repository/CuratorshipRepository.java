package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.Curatorship;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CuratorshipRepository extends JpaRepository<Curatorship, Long> {

    @EntityGraph(attributePaths = {"teacher", "group"})
    List<Curatorship> findAll();

    @EntityGraph(attributePaths = {"teacher", "group"})
    List<Curatorship> findByTeacherId(Long teacherId);

    java.util.Optional<Curatorship> findByTeacherIdAndGroupId(Long teacherId, Long groupId);
}
