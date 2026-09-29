package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DashboardMetricsDto {
    private int pairsThisWeek;
    private int pairsDelta;
    private int freeGapsCount;
    private int conflictsCount;
    private double avgLoadPercent;
    private int unresolvedCount;
    private int currentWeek;
}
