package com.karyakina.schedule.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class WipeScheduleResultDto {
    private int deletedPairs;
    private String scope;
}
