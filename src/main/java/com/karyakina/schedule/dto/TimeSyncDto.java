package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class TimeSyncDto {
    private String status;          // SYNCHRONIZED / DESYNC
    private long diffSeconds;       // разница в секундах
    private String serverTime;      // ISO-8601
    private String clientTime;      // ISO-8601
    private String message;
}
