package com.karyakina.schedule.dto;

import java.util.Map;

/**
 * Ответ администратора на {@link MissingResourceRequest}: выбранный вариант + введённые значения.
 *
 * <p>Пример тела запроса:
 * {@code {"requestId":"9f1c...","actionCode":"USE_CUSTOM_HOURS","payload":{"hours":24}}}
 */
public record ResolutionDecision(String requestId, String actionCode, Map<String, Object> payload) {

    public ResolutionDecision {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public int intValue(String key, int fallback) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public Long longValue(String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
