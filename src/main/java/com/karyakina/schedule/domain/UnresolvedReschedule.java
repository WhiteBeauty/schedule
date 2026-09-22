package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "unresolved_reschedules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UnresolvedReschedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long specialEventId;

    private Long teacherLoadId;

    private Long groupId;

    private String groupName;

    private String disciplineName;

    private String teacherName;

    @Column(nullable = false)
    private LocalDate originalDate;

    private String originalDayOfWeek;

    private LocalTime originalStartTime;

    @Column(length = 1000)
    private String reason;

    @Column(nullable = false)
    private Integer academicYear;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
