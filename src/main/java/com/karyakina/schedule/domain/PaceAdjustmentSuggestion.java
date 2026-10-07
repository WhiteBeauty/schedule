package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "pace_adjustment_suggestions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaceAdjustmentSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_load_id")
    private TeacherLoad teacherLoad;

    @Column(nullable = false)
    private Integer academicYear;

    @Column(nullable = false)
    private Integer semester;

    @Column(nullable = false)
    private Integer currentWeeklyPairs;

    @Column(nullable = false)
    private Integer suggestedWeeklyPairs;

    @Column(nullable = false)
    private Integer effectiveFromWeek;

    private Double expectedHoursToDate;

    private Double actualHoursToDate;

    @Column(length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private Status status = Status.PENDING;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;

    public enum Status {
        PENDING, ACCEPTED, DECLINED
    }
}
