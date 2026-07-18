package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Внутрисистемное уведомление. Используется модулем форс-мажоров для оповещения кандидата
 * на замену и администрации, а также может дублироваться на email (см. NotificationService).
 * Если recipientTeacher == null и forAdmins == true — уведомление адресовано всем администраторам.
 */
@Entity
@Table(name = "notifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "recipient_teacher_id")
    private Teacher recipientTeacher;

    @Column(nullable = false)
    @Builder.Default
    private Boolean forAdmins = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private Type type;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String message;

    /** Ссылка на связанную заявку на замену, если применимо. */
    private Long substitutionRequestId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum Type {
        SUBSTITUTION_REQUEST,   // кандидату: просьба подтвердить замену
        SUBSTITUTION_ACCEPTED,  // всем причастным: замена подтверждена
        SUBSTITUTION_DECLINED,  // администрации: кандидат отказался
        SUBSTITUTION_UNRESOLVED,// администрации: замену найти не удалось
        IMPORT_REPORT,          // администрации: отчёт об импорте
        SCHEDULE_CONFLICT,      // администрации: конфликт при автосоставлении расписания
        SICK_LEAVE,             // администрации / преподавателю: зарегистрирован больничный/форс-мажор
        INFO
    }
}
