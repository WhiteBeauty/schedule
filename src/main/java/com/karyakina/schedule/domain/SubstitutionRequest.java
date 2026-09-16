package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

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
