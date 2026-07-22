package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Журнал аудита. Пишется при чувствительных административных действиях (в первую
 * очередь — назначение/снятие прав администратора), чтобы всегда можно было ответить
 * на вопрос "кто, кого и когда повысил/понизил".
 */
@Entity
@Table(name = "audit_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String action; // например "PROMOTE_TO_ADMIN", "DEMOTE_FROM_ADMIN"

    @Column(nullable = false)
    private String actorUsername; // кто выполнил действие

    @Column(nullable = false)
    private String targetUsername; // над кем выполнено действие

    private String details;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
