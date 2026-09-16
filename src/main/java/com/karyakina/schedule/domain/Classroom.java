package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "classrooms")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Classroom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private Integer capacity;

    @ElementCollection
    @CollectionTable(name = "classroom_allowed_disciplines", joinColumns = @JoinColumn(name = "classroom_id"))
    @Column(name = "discipline_name")
    @Builder.Default
    private List<String> allowedDisciplines = new ArrayList<>();
}
