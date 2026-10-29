package com.karyakina.schedule.component;

import com.karyakina.schedule.domain.AppSetting;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.repository.AppSettingRepository;
import com.karyakina.schedule.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// ограничения которые дал техникум ставим один раз потом их правят в автосоставлении
@Component
@RequiredArgsConstructor
@Slf4j
@org.springframework.core.annotation.Order(3)
public class TeacherAvailabilityDefaults implements ApplicationRunner {

    private static final String APPLIED_KEY = "teacher.availability.defaults";

    private record Rule(String surname, String daysOff, String optionalDays, Integer maxDays,
                        String forbiddenPairs, String undesiredPairs) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("трофимова", null, null, null, "1", null),
            new Rule("машкова", null, "1,5", 4, "1", null),
            new Rule("коврижкина", null, null, 3, null, null),
            new Rule("лосевцев", null, null, null, "4,5", null),
            new Rule("цыганова", null, null, null, null, "4")
    );

    private final TeacherRepository teacherRepository;
    private final AppSettingRepository settingRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppSetting setting = settingRepository.findBySettingKey(APPLIED_KEY)
                .orElseGet(() -> AppSetting.builder().settingKey(APPLIED_KEY).settingValue("").build());
        Set<String> applied = new LinkedHashSet<>();
        for (String s : (setting.getSettingValue() == null ? "" : setting.getSettingValue()).split(",")) {
            if (!s.isBlank()) applied.add(s.trim());
        }
        boolean changed = false;
        List<Teacher> teachers = teacherRepository.findAll();
        for (Rule rule : RULES) {
            if (applied.contains(rule.surname())) continue;
            Teacher teacher = teachers.stream()
                    .filter(t -> t.getFullName() != null
                            && t.getFullName().trim().toLowerCase(Locale.ROOT).replace('ё', 'е').startsWith(rule.surname()))
                    .findFirst().orElse(null);
            if (teacher == null) continue;
            if (isBlank(teacher.getDaysOff()) && isBlank(teacher.getOptionalDays()) && teacher.getMaxDaysPerWeek() == null
                    && isBlank(teacher.getForbiddenPairs()) && isBlank(teacher.getUndesiredPairs())) {
                teacher.setDaysOff(rule.daysOff());
                teacher.setOptionalDays(rule.optionalDays());
                teacher.setMaxDaysPerWeek(rule.maxDays());
                teacher.setForbiddenPairs(rule.forbiddenPairs());
                teacher.setUndesiredPairs(rule.undesiredPairs());
                teacherRepository.save(teacher);
                log.info("Заданы дни и пары преподавателя {}", teacher.getFullName());
            }
            applied.add(rule.surname());
            changed = true;
        }
        if (changed) {
            setting.setSettingValue(String.join(",", applied));
            settingRepository.save(setting);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
