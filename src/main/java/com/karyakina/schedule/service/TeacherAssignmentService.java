package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * МОДУЛЬ АВТОПОДБОРА ПРЕПОДАВАТЕЛЯ.
 *
 * При импорте администратор может не указывать преподавателя для строки
 * "группа + дисциплина" — в этом случае {@link TeacherLoad#getTeacher()} == null.
 * Перед автосоставлением расписания ({@link ScheduleGeneratorService}) этот сервис
 * подбирает по каждой такой записи подходящего преподавателя:
 *
 *  1) если в файле для строки был указан список кандидатов
 *     ({@link TeacherLoad#getCandidateTeacherNames()}) — выбираем среди них;
 *  2) иначе — среди ВСЕХ преподавателей, чья специализация
 *     ({@link Teacher#getSpecialization()}, список дисциплин через запятую)
 *     покрывает дисциплину этой записи. Один преподаватель может вести несколько
 *     дисциплин — они перечисляются в этом поле через запятую; если указана только
 *     одна — считаем, что преподаватель ведёт только её;
 *  3) из подходящих кандидатов выбираем наименее загруженного (по сумме плановых
 *     часов уже закреплённых за ним записей за этот учебный год, включая записи,
 *     назначенные этим же прогоном подбора — так нагрузка распределяется равномерно,
 *     а не "весь список кандидатов ведёт первый по алфавиту").
 *
 * Это ЧИСТОЕ вычисление без сохранения в БД: обёртка (ScheduleGeneratorService)
 * решает, фиксировать ли результат (только когда расписание реально сохраняется,
 * а не при просмотре черновика).
 */
@Service
@Slf4j
public class TeacherAssignmentService {

    /** Итог подбора: teacherLoadId -> подобранный преподаватель, плюс нерешённые случаи. */
    public static class Resolution {
        public final Map<Long, Teacher> resolvedTeacherByLoadId = new HashMap<>();
        public final List<String> conflicts = new ArrayList<>();
    }

    public Resolution resolve(List<TeacherLoad> allLoadsForYear, List<Teacher> allTeachers) {
        Resolution result = new Resolution();

        // Стартовая загрузка каждого преподавателя — часы по уже явно назначенным
        // записям. Используется для балансировки: подбор отдаёт предпочтение тому,
        // у кого сейчас меньше всего плановых часов ("у кого есть свободные часы").
        Map<Long, Integer> hoursByTeacher = new HashMap<>();
        for (TeacherLoad l : allLoadsForYear) {
            if (l.getTeacher() != null) {
                hoursByTeacher.merge(l.getTeacher().getId(), nz(l.getPlannedHours()), Integer::sum);
            }
        }

        List<TeacherLoad> unassigned = allLoadsForYear.stream()
                .filter(l -> l.getTeacher() == null)
                .sorted(Comparator.comparing(l -> l.getDiscipline().getName()))
                .toList();

        for (TeacherLoad load : unassigned) {
            List<Teacher> pool = candidatePool(load, allTeachers);

            if (pool.isEmpty()) {
                result.conflicts.add(String.format(
                        "Не удалось подобрать преподавателя: дисциплина «%s», группа %s — " +
                        "нет преподавателя ни в списке кандидатов, ни с подходящей специализацией " +
                        "(заполните Teacher.specialization или колонку \"Возможные преподаватели\")",
                        load.getDiscipline().getName(), load.getGroup().getName()));
                continue;
            }

            Teacher chosen = pool.stream()
                    .min(Comparator.<Teacher>comparingInt(t -> hoursByTeacher.getOrDefault(t.getId(), 0))
                            .thenComparing(Teacher::getFullName))
                    .orElseThrow();

            result.resolvedTeacherByLoadId.put(load.getId(), chosen);
            hoursByTeacher.merge(chosen.getId(), nz(load.getPlannedHours()), Integer::sum);
        }

        return result;
    }

    private List<Teacher> candidatePool(TeacherLoad load, List<Teacher> allTeachers) {
        if (load.getCandidateTeacherNames() != null && !load.getCandidateTeacherNames().isBlank()) {
            List<Teacher> named = new ArrayList<>();
            for (String token : load.getCandidateTeacherNames().split(",")) {
                String name = token.trim();
                if (name.isEmpty()) continue;
                allTeachers.stream()
                        .filter(t -> t.getFullName().equalsIgnoreCase(name))
                        .findFirst()
                        .ifPresent(named::add);
            }
            if (!named.isEmpty()) return named;
            // Явно указанные кандидаты не найдены в базе — не подменяем их тихо
            // произвольным преподавателем, дальше решает fallback по специализации,
            // только если у записи её тоже нет никакого варианта — это уйдёт в конфликт.
        }

        String disciplineName = load.getDiscipline().getName();
        return allTeachers.stream()
                .filter(t -> matchesSpecialization(t, disciplineName))
                .toList();
    }

    /**
     * true, если преподаватель ведёт дисциплину disciplineName согласно
     * Teacher.specialization (список через запятую, нестрогое вхождение подстроки
     * в обе стороны — совпадает с логикой оценки в ScheduleGeneratorService).
     */
    public static boolean matchesSpecialization(Teacher teacher, String disciplineName) {
        if (teacher.getSpecialization() == null || teacher.getSpecialization().isBlank()) return false;
        if (disciplineName == null) return false;
        String discNorm = disciplineName.toLowerCase(Locale.ROOT);
        for (String token : teacher.getSpecialization().split(",")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && (discNorm.contains(t) || t.contains(discNorm))) {
                return true;
            }
        }
        return false;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
