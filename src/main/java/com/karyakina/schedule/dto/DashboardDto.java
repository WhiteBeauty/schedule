package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data @Builder
public class DashboardDto {
    // Информация о преподавателе
    private Long id;
    private String fullName;
    private String initials;
    private String department;
    private String position;
    private String email;
    private String phone;
    private Double rate;
    private LocalDate birthDate;

    // Продуктивность
    private ProductivityBarDto productivity;

    // Нагрузка
    private List<TeacherLoadRowDto> loads;
    private int totalDisciplines;
    private int totalGroups;
    private int totalHours1;
    private int totalHours2;
    private int totalYearHours;
    private int totalRemaining;
    private int totalRead;

    // Профиль для модального окна
    private TeacherProfileDto profile;

    @Data @Builder
    public static class ProductivityBarDto {
        private double index;
        private double target;
        private String level; // HIGH, MEDIUM, LOW
        private String color; // success, warning, danger
        private double planCompletionPercent;
        private double timelinessPercent;
        private double accuracyPercent;
    }

    @Data @Builder
    public static class TeacherLoadRowDto {
        private Long id;
        private String discipline;
        private String controlPoint1;
        private String controlPoint2;
        private int plannedHoursFirst;
        private int plannedHoursSecond;
        private int totalPlannedHours;
        private int remainingHours;
        private int readHours;
        private String groupName;
    }
}