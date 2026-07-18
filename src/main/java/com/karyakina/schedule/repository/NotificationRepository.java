package com.karyakina.schedule.repository;

import com.karyakina.schedule.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipientTeacherIdOrderByCreatedAtDesc(Long teacherId);

    List<Notification> findByForAdminsTrueOrderByCreatedAtDesc();

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.recipientTeacher.id = :teacherId AND n.isRead = false")
    long countUnreadForTeacher(@Param("teacherId") Long teacherId);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.forAdmins = true AND n.isRead = false")
    long countUnreadForAdmins();
}
