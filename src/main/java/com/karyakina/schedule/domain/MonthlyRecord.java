package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "monthly_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MonthlyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_load_id")
    @JsonIgnore
    private TeacherLoad teacherLoad;

    @Column(name = "record_month", nullable = false)
    private Integer month; // 1..12

    @Column(name = "record_year", nullable = false)
    private Integer year;

    @Column(nullable = false)
    private Integer hours;

    /** Скорректированное значение (если ручная правка) */
    private Integer adjustedHours;

    private String note;
    private String changedBy;

    @CreationTimestamp
    private LocalDateTime changedAt;

    @Transient
    public int getEffectiveHours() {
        return adjustedHours != null ? adjustedHours : hours;
    }
}
