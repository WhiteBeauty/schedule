package com.karyakina.schedule.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ИНТЕРАКТИВНЫЙ РЕЖИМ. Проблема, которую алгоритм НЕ решает молча и НЕ бросает исключением:
 * вместо падения возвращается вопрос администратору с готовыми вариантами действий,
 * а фронтенд показывает его модальным окном с кнопками (см. static/js/schedule-generation.js).
 *
 * <p>Пример сообщения:
 * «Для предмета "Математика" (ИС-21) расхождение по часам: в файле нагрузки 10 ч,
 * при ручном вводе указано 50 ч.» с вариантами:
 * 1) Утвердить часы из файла (10); 2) Утвердить часы из ручного ввода (50); 3) Указать своё количество часов.
 */
public record MissingResourceRequest(
        String id,
        Code code,
        Severity severity,
        String title,
        String message,
        Map<String, Object> context,
        List<ResolutionOption> options
) {

    public enum Code {
        /** Часы из файла нагрузки не совпадают с ручным вводом. */
        HOURS_MISMATCH,
        /** Одна и та же тройка преподаватель+дисциплина+группа задана несколькими записями. */
        DUPLICATE_LOAD,
        /** Часов в неделю больше, чем физически возможно (например, 50 ч на одну дисциплину). */
        HOURS_IMPLAUSIBLE,
        /** Для нагрузки не удалось определить преподавателя. */
        NO_TEACHER_FOR_DISCIPLINE,
        /** Недельная нагрузка преподавателя выше лимита (36 ч). */
        TEACHER_OVERLOAD,
        /** У одной группы по одному предмету больше настроенного часового предела в неделю. */
        SUBJECT_GROUP_OVERLOAD,
        /** Не хватает свободных аудиторий. */
        NO_ROOM_AVAILABLE,
        /** Не хватает слотов в сетке у группы или преподавателя. */
        GRID_CAPACITY,
        /** Упираемся в лимит пар одной дисциплины в день. */
        SUBJECT_LIMIT,
        /** Часть пар не удалось расставить. */
        UNPLACED_LESSONS,
        /** Не нашли замену заболевшему преподавателю. */
        SUBSTITUTE_NOT_FOUND,
        /** Техническая ошибка — показываем текст, а не пустое окно. */
        DATA_ERROR
    }

    public enum Severity {
        /** Без ответа администратора расписание останется неполным. */
        BLOCKING,
        /** Можно продолжать; ответ улучшит результат. */
        WARNING
    }

    /** Вариант действия. {@code input} != null, если пользователю нужно что-то ввести или выбрать. */
    public record ResolutionOption(String actionCode, String label, Map<String, Object> payload, InputSpec input) {

        public ResolutionOption {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }

        public static ResolutionOption of(String actionCode, String label) {
            return new ResolutionOption(actionCode, label, Map.of(), null);
        }

        public static ResolutionOption of(String actionCode, String label, Map<String, Object> payload) {
            return new ResolutionOption(actionCode, label, payload, null);
        }

        public static ResolutionOption withInput(String actionCode, String label, InputSpec input) {
            return new ResolutionOption(actionCode, label, Map.of(), input);
        }
    }

    /** Описание поля ввода на фронтенде: NUMBER (число), SELECT (выбор из списка). */
    public record InputSpec(String type, String label, String field, Integer min, Integer max,
                            Object defaultValue, List<Choice> choices) {

        public static InputSpec number(String label, String field, int min, int max, Object defaultValue) {
            return new InputSpec("NUMBER", label, field, min, max, defaultValue, List.of());
        }

        public static InputSpec select(String label, String field, List<Choice> choices) {
            return new InputSpec("SELECT", label, field, null, null, null,
                    choices == null ? List.of() : List.copyOf(choices));
        }
    }

    public record Choice(String value, String label) {
    }

    /** Коды действий: их же принимает {@link ResolutionDecision#actionCode()}. */
    public static final class Actions {
        public static final String USE_FILE_HOURS = "USE_FILE_HOURS";
        public static final String USE_MANUAL_HOURS = "USE_MANUAL_HOURS";
        public static final String USE_CUSTOM_HOURS = "USE_CUSTOM_HOURS";
        public static final String SUM_DUPLICATES = "SUM_DUPLICATES";
        public static final String KEEP_FIRST_DUPLICATE = "KEEP_FIRST_DUPLICATE";
        public static final String ASSIGN_TEACHER = "ASSIGN_TEACHER";
        public static final String SKIP_LOAD = "SKIP_LOAD";
        public static final String REDUCE_HOURS_TO_FIT = "REDUCE_HOURS_TO_FIT";
        public static final String RAISE_TEACHER_LIMIT = "RAISE_TEACHER_LIMIT";
        public static final String INCREASE_PAIRS_PER_DAY = "INCREASE_PAIRS_PER_DAY";
        public static final String RELAX_SUBJECT_PER_DAY = "RELAX_SUBJECT_PER_DAY";
        public static final String KEEP_AS_IS = "KEEP_AS_IS";

        private Actions() {
        }
    }

    public MissingResourceRequest {
        id = id == null ? UUID.randomUUID().toString() : id;
        context = context == null ? Map.of() : Map.copyOf(context);
        options = options == null ? List.of() : List.copyOf(options);
    }

    public static Builder builder(Code code, String title) {
        return new Builder(code, title);
    }

    public static MissingResourceRequest technical(String message) {
        return builder(Code.DATA_ERROR, "Не удалось выполнить автосоставление")
                .severity(Severity.BLOCKING)
                .message(message)
                .option(ResolutionOption.of(Actions.KEEP_AS_IS, "Понятно, закрыть"))
                .build();
    }

    public static final class Builder {
        private final Code code;
        private final String title;
        private Severity severity = Severity.BLOCKING;
        private String message = "";
        private final Map<String, Object> context = new LinkedHashMap<>();
        private final List<ResolutionOption> options = new ArrayList<>();

        private Builder(Code code, String title) {
            this.code = code;
            this.title = title;
        }

        public Builder severity(Severity value) {
            this.severity = value;
            return this;
        }

        public Builder message(String value) {
            this.message = value;
            return this;
        }

        public Builder context(String key, Object value) {
            if (value != null) {
                this.context.put(key, value);
            }
            return this;
        }

        public Builder option(ResolutionOption option) {
            this.options.add(option);
            return this;
        }

        public MissingResourceRequest build() {
            return new MissingResourceRequest(UUID.randomUUID().toString(), code, severity, title, message,
                    context, options);
        }
    }
}
