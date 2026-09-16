package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "lesson_instances", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"schedule_id", "lesson_date"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class LessonInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    @Column(name = "lesson_date", nullable = false)
    private LocalDate lessonDate;

    @Column(nullable = false)
    private Integer academicYear;

    @Column(nullable = false)
    private Integer durationHours;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PLANNED;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_load_id")
    private TeacherLoad teacherLoad;

    @ManyToOne(optional = false)
    @JoinColumn(name = "original_teacher_id")
    private Teacher originalTeacher;

    @ManyToOne(optional = false)
    @JoinColumn(name = "actual_teacher_id")
    private Teacher actualTeacher;

    private String note;

    private LocalDateTime confirmedAt;
    private LocalDateTime cancelledAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private Long cancelledBySpecialEventId;

    public enum Status {
        PLANNED, CONFIRMED, CANCELLED, REPLACED, INDEPENDENT_WORK
    }
}
