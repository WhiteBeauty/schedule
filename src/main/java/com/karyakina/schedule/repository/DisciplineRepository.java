package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.Discipline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DisciplineRepository extends JpaRepository<Discipline, Long> {
    Optional<Discipline> findByname(String name);
    Optional<Discipline> findByNameIgnoreCase(String name);
}
