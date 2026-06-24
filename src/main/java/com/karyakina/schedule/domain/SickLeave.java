package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "sick_leaves")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SickLeave {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    private String reason;

    @Column(nullable = false)
    private Integer academicYear;
}
