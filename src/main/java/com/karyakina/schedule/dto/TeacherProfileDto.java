package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data @Builder
public class TeacherProfileDto {
    private Long id;
    private String fullName;
    private String initials;
    private String department;
    private String position;
    private String email;
    private String phone;
    private Double rate;
    private LocalDate birthDate;

    private int totalDisciplines;
    private int totalPlannedHours;
    private int totalReadHours;
    private int totalRemainingHours;
    private double avgProductivity;
    private double productivityIndex;
    private String productivityLevel;

    private List<LoadSummaryDto> loads;
    private List<MonthlyHoursDto> monthlyDynamics;

    @Data @Builder
    public static class LoadSummaryDto {
        private Long loadId;
        private String discipline;
        private String groupName;
        private int planned;
        private int read;
        private int remaining;
        private double completion;
    }

    @Data @Builder
    public static class MonthlyHoursDto {
        private String month;
        private int hours;
    }
}
