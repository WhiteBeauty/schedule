package com.karyakina.schedule.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Простое хранилище "ключ-значение" для общих настроек приложения — например,
 * обеденного перерыва по умолчанию для всего потока (когда для конкретной группы
 * не задан свой обед — см. StudyGroup.lunchStart/lunchEnd).
 */
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
