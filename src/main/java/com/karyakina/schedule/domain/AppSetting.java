package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "app_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AppSetting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String settingKey;

    private String settingValue;
}
