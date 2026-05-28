package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "teachers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Teacher {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    private String department;
    private String position;
    private String email;
    private String phone;

    /** Ставка (1.0, 0.75, 0.5 и т.д.) */
    private Double rate;

    private LocalDate birthDate;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "teacher", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TeacherLoad> loads = new ArrayList<>();

    @OneToMany(mappedBy = "teacher", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Curatorship> curatorships = new ArrayList<>();

    @OneToOne(mappedBy = "teacher", cascade = CascadeType.ALL)
    private User user;
}
