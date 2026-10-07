package com.karyakina.schedule.domain;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CuratorEvent {
    private LocalDate date;
    private Double hours;
    private String description;
}
