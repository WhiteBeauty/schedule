package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class TimeSyncDto {
    private String status;
    private long diffSeconds;
    private String serverTime;
    private String clientTime;
    private String message;
}
