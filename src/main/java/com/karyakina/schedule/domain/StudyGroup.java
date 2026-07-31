package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "study_groups")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StudyGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String specialty;
    private Integer course;
    private Integer studentCount;

    // Обеденный перерыв для ЭТОЙ группы. Если оба поля не заданы (null) — при
    // составлении расписания используется общий обед для всего потока (см.
    // SettingsService.getGlobalLunchWindow), если он настроен. Если задано хотя бы
    // одно из полей здесь — оно имеет приоритет над общим обедом для этой группы.
    private java.time.LocalTime lunchStart;
    private java.time.LocalTime lunchEnd;
}
