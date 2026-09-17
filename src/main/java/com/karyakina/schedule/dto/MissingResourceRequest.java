package com.karyakina.schedule.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        HOURS_MISMATCH,
        DUPLICATE_LOAD,
        HOURS_IMPLAUSIBLE,
        NO_TEACHER_FOR_DISCIPLINE,
        TEACHER_OVERLOAD,
        SUBJECT_GROUP_OVERLOAD,
        NO_ROOM_AVAILABLE,
        GRID_CAPACITY,
        SUBJECT_LIMIT,
        UNPLACED_LESSONS,
        SUBSTITUTE_NOT_FOUND,
        DATA_ERROR
    }

    public enum Severity {
        BLOCKING,
        WARNING
    }

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

    public static final class Actions {
        public static final String USE_FILE_HOURS = "USE_FILE_HOURS";
        public static final String USE_MANUAL_HOURS = "USE_MANUAL_HOURS";
        public static final String USE_CUSTOM_HOURS = "USE_CUSTOM_HOURS";
        public static final String SUM_DUPLICATES = "SUM_DUPLICATES";
        public static final String KEEP_FIRST_DUPLICATE = "KEEP_FIRST_DUPLICATE";
        public static final String ASSIGN_TEACHER = "ASSIGN_TEACHER";
        public static final String SKIP_LOAD = "SKIP_LOAD";
        public static final String REDUCE_HOURS_TO_FIT = "REDUCE_HOURS_TO_FIT";
        public static final String INCREASE_PAIRS_PER_DAY = "INCREASE_PAIRS_PER_DAY";
        public static final String RAISE_GROUP_WEEK_LIMIT = "RAISE_GROUP_WEEK_LIMIT";
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
