package com.karyakina.schedule.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "teacher_loads")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeacherLoad {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    @ManyToOne(optional = false)
    @JoinColumn(name = "group_id")
    private StudyGroup group;

    @ManyToOne(optional = false)
    @JoinColumn(name = "discipline_id")
    private Discipline discipline;

    @Column(nullable = false)
    private Integer plannedHours;

    @Column(nullable = false)
    private Integer firstSemesterHours;

    @Column(nullable = false)
    private Integer secondSemesterHours;

    private String controlPointType1;
    private String controlPointType2;

    @Column(nullable = false)
    private Integer readHours;

    @Column(nullable = false)
    private Integer academicYear; // например, 2024

    @OneToMany(mappedBy = "teacherLoad", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 20)
    @JsonIgnore
    private List<ControlPoint> controlPoints = new ArrayList<>();

    @OneToMany(mappedBy = "teacherLoad", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 20)
    @JsonIgnore
    private List<MonthlyRecord> monthlyRecords = new ArrayList<>();

    @Transient
    public int getRemainingHours() {
        return plannedHours - readHours;
    }

    @Transient
    public double getCompletionPercent() {
        if (plannedHours == 0) return 0;
        return (readHours * 100.0) / plannedHours;
    }

    @Transient
    public int getTotalPlannedHours() {
        return firstSemesterHours + secondSemesterHours;
    }

    @Transient
    public String getControlPoint1() {
        return controlPointType1 != null ? controlPointType1 : "-";
    }

    @Transient
    public String getControlPoint2() {
        return controlPointType2 != null ? controlPointType2 : "-";
    }

    public enum ControlPointType {
        DZ, ZACHET, EKZAMEN, E, NONE
    }
}
