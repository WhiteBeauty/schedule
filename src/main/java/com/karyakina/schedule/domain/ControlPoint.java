package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "control_points")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ControlPoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_load_id")
    @JsonIgnore
    private TeacherLoad teacherLoad;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_type", nullable = false)
    private ControlPointType type;

    private LocalDate plannedDate;
    private LocalDate actualDate;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ControlPointStatus status = ControlPointStatus.PLANNED;

    public enum ControlPointType {
        KR, ZACHET, EXAM
    }

    public enum ControlPointStatus {
        PLANNED, ON_TIME, OVERDUE
    }
}
