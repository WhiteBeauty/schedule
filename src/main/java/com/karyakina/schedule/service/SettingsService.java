package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.AppSetting;
import com.karyakina.schedule.repository.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Optional;

/**
 * Настройка обеда "для всего потока" (глобальный обеденный перерыв по умолчанию —
 * используется для групп, у которых свой обед не задан отдельно, см.
 * StudyGroup.lunchStart/lunchEnd). Хранится в AppSetting как две строки "HH:mm".
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettingsService {

    private static final String GLOBAL_LUNCH_START_KEY = "global.lunch.start";
    private static final String GLOBAL_LUNCH_END_KEY = "global.lunch.end";

    private final AppSettingRepository settingRepository;

    /** Возвращает [начало, конец] общего обеда, либо null, если он не настроен вовсе. */
    public LocalTime[] getGlobalLunchWindow() {
        Optional<AppSetting> start = settingRepository.findBySettingKey(GLOBAL_LUNCH_START_KEY);
        Optional<AppSetting> end = settingRepository.findBySettingKey(GLOBAL_LUNCH_END_KEY);
        if (start.isEmpty() || end.isEmpty()) return null;
        try {
            return new LocalTime[]{
                    LocalTime.parse(start.get().getSettingValue()),
                    LocalTime.parse(end.get().getSettingValue())
            };
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public void setGlobalLunch(LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("Начало обеда должно быть раньше окончания");
        }
        upsert(GLOBAL_LUNCH_START_KEY, start.toString());
        upsert(GLOBAL_LUNCH_END_KEY, end.toString());
    }

    @Transactional
    public void clearGlobalLunch() {
        settingRepository.findBySettingKey(GLOBAL_LUNCH_START_KEY).ifPresent(settingRepository::delete);
        settingRepository.findBySettingKey(GLOBAL_LUNCH_END_KEY).ifPresent(settingRepository::delete);
    }

    private void upsert(String key, String value) {
        AppSetting setting = settingRepository.findBySettingKey(key)
                .orElseGet(() -> AppSetting.builder().settingKey(key).build());
        setting.setSettingValue(value);
        settingRepository.save(setting);
    }
}
