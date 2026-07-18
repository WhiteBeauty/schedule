package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Заявка на замену конкретного занятия ({@link LessonInstance}) на время больничного/форс-мажора.
 * Создаётся автоматически модулем обработки форс-мажоров при регистрации {@link SickLeave}.
 * Кандидату отправляется уведомление ({@link Notification}); после его подтверждения
 * ScheduleService.replaceInstance(...) переносит часы и обновляет тарификацию.
 */
@Entity
@Table(name = "substitution_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SubstitutionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "lesson_instance_id")
    private LessonInstance lessonInstance;

    @ManyToOne(optional = false)
    @JoinColumn(name = "sick_leave_id")
    private SickLeave sickLeave;

    @ManyToOne(optional = false)
    @JoinColumn(name = "original_teacher_id")
    private Teacher originalTeacher;

    @ManyToOne(optional = false)
    @JoinColumn(name = "candidate_teacher_id")
    private Teacher candidateTeacher;

    /**
     * Причина, по которой кандидат был выбран (номер приоритета алгоритма замены):
     * 1 = ведёт эту же дисциплину у других групп,
     * 2 = та же кафедра, свободное окно,
     * 3 = есть резерв часов в плановой нагрузке (иначе будет отмечено как переработка).
     */
    private Integer priorityRank;
    private String priorityReason;

    @Column(nullable = false)
    private Boolean overload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PENDING;

    @CreationTimestamp
    private LocalDateTime createdAt;
    private LocalDateTime respondedAt;

    public enum Status {
        PENDING, ACCEPTED, DECLINED, EXPIRED
    }
}
