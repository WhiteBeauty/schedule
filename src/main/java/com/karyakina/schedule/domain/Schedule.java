package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Entity
@Table(name = "schedules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Schedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_load_id")
    private TeacherLoad teacherLoad;

    @Column(nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private String classroom;

    private Integer academicWeek; // 1-18, null = каждая неделя

    @Column(nullable = false)
    private Integer academicYear;
}
