package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "tarifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tarification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Integer academicYear;

    private String sourceFileName;

    private String importedBy;

    @CreationTimestamp
    private LocalDateTime importedAt;
}
