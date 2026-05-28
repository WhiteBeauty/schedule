package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "disciplines")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Discipline {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String code;
}
