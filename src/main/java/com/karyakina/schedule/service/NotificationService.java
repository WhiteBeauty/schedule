package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Notification;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Уведомления модуля форс-мажоров и импорта. Хранятся внутри системы (таблица notifications)
 * и отображаются в разделе "Уведомления" каждому пользователю. Точка расширения sendEmail(...)
 * оставлена как заглушка — при необходимости подключить реальный SMTP/мессенджер, письмо
 * достаточно отправить в этом единственном месте, вся остальная логика уже готова.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;

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
        Notification n = Notification.builder()
                .recipientTeacher(teacher)
                .forAdmins(false)
                .type(type)
                .title(title)
                .message(message)
                .substitutionRequestId(substitutionRequestId)
                .build();
        n = notificationRepository.save(n);
        sendEmail(teacher, title, message);
        return n;
    }

    @Transactional
    public Notification notifyAdmins(Notification.Type type, String title, String message,
                                      Long substitutionRequestId) {
        Notification n = Notification.builder()
                .recipientTeacher(null)
                .forAdmins(true)
                .type(type)
                .title(title)
                .message(message)
                .substitutionRequestId(substitutionRequestId)
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

    /**
     * Заглушка отправки email/мессенджера. В текущей конфигурации уведомление доставляется
     * только внутри системы (см. notifyTeacher/notifyAdmins) и пишется в лог; чтобы включить
     * реальную отправку, подключите spring-boot-starter-mail и замените тело метода на
     * mailSender.send(...), используя teacher.getEmail().
     */
    private void sendEmail(Teacher teacher, String title, String message) {
        if (teacher.getEmail() == null || teacher.getEmail().isBlank()) return;
        log.info("[EMAIL-STUB] To: {} | {} | {}", teacher.getEmail(), title, message);
    }
}
