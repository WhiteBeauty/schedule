package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    private Double rate;

    private LocalDate birthDate;

    private Integer maxPairsPerDay;

    // дни и пары через запятую пн это 1 первая пара это 1
    @Column(length = 20)
    private String daysOff;

    @Column(length = 20)
    private String optionalDays;

    private Integer maxDaysPerWeek;

    @Column(length = 20)
    private String forbiddenPairs;

    @Column(length = 20)
    private String undesiredPairs;

    @Column(length = 2048)
    private String specialization;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "teacher", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @JsonIgnore
    private List<TeacherLoad> loads = new ArrayList<>();

    @OneToMany(mappedBy = "teacher", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @JsonIgnore
    private List<Curatorship> curatorships = new ArrayList<>();

    @OneToOne(mappedBy = "teacher", cascade = CascadeType.ALL)
    @JsonIgnore
    private User user;
}
