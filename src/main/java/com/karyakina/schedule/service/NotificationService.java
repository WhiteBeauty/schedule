package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Notification;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;

    public List<Notification> findForTeacher(Long teacherId) {
        return notificationRepository.findByRecipientTeacherIdOrderByCreatedAtDesc(teacherId);
    }

    public List<Notification> findForAdmins() {
        return notificationRepository.findByForAdminsTrueOrderByCreatedAtDesc();
    }

    public long countUnreadForTeacher(Long teacherId) {
        return notificationRepository.countUnreadForTeacher(teacherId);
    }

    public long countUnreadForAdmins() {
        return notificationRepository.countUnreadForAdmins();
    }

    @Transactional
    public Notification notifyTeacher(Teacher teacher, Notification.Type type, String title,
                                       String message, Long substitutionRequestId) {
        return notifyTeacher(teacher, type, title, message, substitutionRequestId, null, false);
    }

    @Transactional
    public Notification notifyTeacher(Teacher teacher, Notification.Type type, String title,
                                       String message, Long substitutionRequestId,
                                       String linkUrl, boolean critical) {
        Notification n = Notification.builder()
                .recipientTeacher(teacher)
                .forAdmins(false)
                .type(type)
                .title(title)
                .message(message)
                .substitutionRequestId(substitutionRequestId)
                .linkUrl(linkUrl)
                .build();
        n = notificationRepository.save(n);
        if (critical) {
            sendEmail(teacher, title, message);
        }
        return n;
    }

    @Transactional
    public Notification notifyAdmins(Notification.Type type, String title, String message,
                                      Long substitutionRequestId) {
        return notifyAdmins(type, title, message, substitutionRequestId, null);
    }

    @Transactional
    public Notification notifyAdmins(Notification.Type type, String title, String message,
                                      Long substitutionRequestId, String linkUrl) {
        Notification n = Notification.builder()
                .recipientTeacher(null)
                .forAdmins(true)
                .type(type)
                .title(title)
                .message(message)
                .substitutionRequestId(substitutionRequestId)
                .linkUrl(linkUrl)
                .build();
        return notificationRepository.save(n);
    }

    @Transactional
    public Notification markRead(Long id) {
        Notification n = notificationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Уведомление не найдено: " + id));
        n.setIsRead(true);
        return notificationRepository.save(n);
    }

    @Transactional
    public int markAllReadForTeacher(Long teacherId) {
        List<Notification> unread = notificationRepository.findByRecipientTeacherIdOrderByCreatedAtDesc(teacherId)
                .stream().filter(n -> !Boolean.TRUE.equals(n.getIsRead())).toList();
        unread.forEach(n -> n.setIsRead(true));
        notificationRepository.saveAll(unread);
        return unread.size();
    }

    @Transactional
    public int markAllReadForAdmins() {
        List<Notification> unread = notificationRepository.findByForAdminsTrueOrderByCreatedAtDesc()
                .stream().filter(n -> !Boolean.TRUE.equals(n.getIsRead())).toList();
        unread.forEach(n -> n.setIsRead(true));
        notificationRepository.saveAll(unread);
        return unread.size();
    }

    private void sendEmail(Teacher teacher, String title, String message) {
        if (teacher.getEmail() == null || teacher.getEmail().isBlank()) return;
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo(teacher.getEmail());
            mail.setSubject(title);
            mail.setText(message);
            mailSender.send(mail);
            log.info("Email отправлен: {} -> {}", title, teacher.getEmail());
        } catch (Exception e) {
            log.warn("Не удалось отправить email на {} ({}): {}. Уведомление осталось " +
                    "доступным во внутреннем центре уведомлений.", teacher.getEmail(), title, e.getMessage());
        }
    }
}
