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
}
