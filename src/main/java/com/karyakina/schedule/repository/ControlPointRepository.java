package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.ControlPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ControlPointRepository extends JpaRepository<ControlPoint, Long> {
    List<ControlPoint> findByTeacherLoadId(Long teacherLoadId);
}
