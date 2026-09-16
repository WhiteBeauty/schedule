package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "special_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SpecialEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "group_id")
    private StudyGroup group;

    @ManyToOne
    @JoinColumn(name = "discipline_id")
    private Discipline discipline;

    @ManyToOne
    @JoinColumn(name = "teacher_load_id")
    private TeacherLoad teacherLoad;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private Integer academicYear;

    private String createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private String note;

    public enum Type {
        EXAM, PRODUCTION_PRACTICE, STUDY_PRACTICE, DRIVING
    }
}
