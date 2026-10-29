package com.karyakina.schedule.service.generator;

import com.karyakina.schedule.domain.Teacher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

// индексы дней и пар здесь с нуля как в сетке генератора
public record TeacherAvailability(Set<Integer> daysOff, Set<Integer> optionalDays, int maxDays,
                                  Set<Integer> forbiddenPairs, Set<Integer> undesiredPairs) {

    public static final TeacherAvailability NONE = new TeacherAvailability(Set.of(), Set.of(), 0, Set.of(), Set.of());

    private static final String[] DAY_SHORT = {"пн", "вт", "ср", "чт", "пт"};

    public TeacherAvailability {
        daysOff = daysOff == null ? Set.of() : Set.copyOf(daysOff);
        optionalDays = optionalDays == null ? Set.of() : Set.copyOf(optionalDays);
        forbiddenPairs = forbiddenPairs == null ? Set.of() : Set.copyOf(forbiddenPairs);
        undesiredPairs = undesiredPairs == null ? Set.of() : Set.copyOf(undesiredPairs);
        maxDays = maxDays <= 0 || maxDays >= GenerationGrid.days() ? 0 : maxDays;
    }

    public static TeacherAvailability of(Teacher teacher) {
        if (teacher == null) {
            return NONE;
        }
        Set<Integer> off = parse(teacher.getDaysOff(), GenerationGrid.days());
        Set<Integer> optional = parse(teacher.getOptionalDays(), GenerationGrid.days());
        optional.removeAll(off);
        Set<Integer> forbidden = parse(teacher.getForbiddenPairs(), GenerationGrid.pairsPerDay());
        Set<Integer> undesired = parse(teacher.getUndesiredPairs(), GenerationGrid.pairsPerDay());
        undesired.removeAll(forbidden);
        int max = teacher.getMaxDaysPerWeek() == null ? 0 : teacher.getMaxDaysPerWeek();
        return new TeacherAvailability(off, optional, max, forbidden, undesired);
    }

    // строка вида 1,5 где пн это 1
    public static Set<Integer> parse(String raw, int limit) {
        Set<Integer> result = new TreeSet<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String token : raw.split("[,;\\s]+")) {
            try {
                int value = Integer.parseInt(token.trim());
                if (value >= 1 && value <= limit) {
                    result.add(value - 1);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    public static String format(Set<Integer> zeroBased) {
        List<String> parts = new ArrayList<>();
        new TreeSet<>(zeroBased).forEach(i -> parts.add(String.valueOf(i + 1)));
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    public boolean isEmpty() {
        return daysOff.isEmpty() && maxDays == 0 && forbiddenPairs.isEmpty() && undesiredPairs.isEmpty();
    }

    public boolean dayOff(int dayIdx) {
        return daysOff.contains(dayIdx);
    }

    public boolean pairForbidden(int pairIdx) {
        return forbiddenPairs.contains(pairIdx);
    }

    public boolean pairUndesired(int pairIdx) {
        return undesiredPairs.contains(pairIdx);
    }

    // можно ли поставить пару в этот день если уже заняты usedDays
    public boolean canUseDay(boolean[] usedDays, int dayIdx) {
        if (daysOff.contains(dayIdx)) {
            return false;
        }
        if (maxDays == 0 || (usedDays != null && usedDays[dayIdx])) {
            return true;
        }
        int used = 0;
        int usedOptional = 0;
        for (int d = 0; usedDays != null && d < usedDays.length; d++) {
            if (!usedDays[d]) continue;
            used++;
            if (optionalDays.contains(d)) usedOptional++;
        }
        if (used >= maxDays) {
            return false;
        }
        if (optionalDays.contains(dayIdx)) {
            int fixedDays = GenerationGrid.days() - daysOff.size() - optionalDays.size();
            return usedOptional < Math.max(0, maxDays - fixedDays);
        }
        return true;
    }

    public int workDaysPerWeek() {
        int open = GenerationGrid.days() - daysOff.size();
        return maxDays == 0 ? open : Math.min(open, maxDays);
    }

    public int slotsPerWeek() {
        return workDaysPerWeek() * (GenerationGrid.pairsPerDay() - forbiddenPairs.size());
    }

    public String describe() {
        List<String> parts = new ArrayList<>();
        if (!daysOff.isEmpty()) parts.add("не работает: " + days(daysOff));
        if (maxDays > 0) {
            parts.add("не больше " + maxDays + " дн. в неделю"
                    + (optionalDays.isEmpty() ? "" : " (" + days(optionalDays).replace(", ", " или ") + " на выбор)"));
        }
        if (!forbiddenPairs.isEmpty()) parts.add("нельзя пары: " + pairs(forbiddenPairs));
        if (!undesiredPairs.isEmpty()) parts.add("нежелательно: " + pairs(undesiredPairs));
        return String.join("; ", parts);
    }

    private static String days(Set<Integer> set) {
        List<String> names = new ArrayList<>();
        new TreeSet<>(set).forEach(d -> names.add(d >= 0 && d < DAY_SHORT.length ? DAY_SHORT[d] : String.valueOf(d + 1)));
        return String.join(", ", names);
    }

    private static String pairs(Set<Integer> set) {
        Set<String> names = new LinkedHashSet<>();
        new TreeSet<>(set).forEach(p -> names.add(String.valueOf(p + 1)));
        return String.join(", ", names);
    }
}
