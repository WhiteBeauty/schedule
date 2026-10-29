package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.CuratorEvent;
import com.karyakina.schedule.domain.Curatorship;
import com.karyakina.schedule.domain.StudyGroup;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.dto.MonthEventDto;
import com.karyakina.schedule.dto.MonthLessonDto;
import com.karyakina.schedule.dto.MonthScheduleDto;
import com.karyakina.schedule.repository.CuratorshipRepository;
import com.karyakina.schedule.repository.StudyGroupRepository;
import com.karyakina.schedule.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CuratorshipService {

    public static final int DEFAULT_HOURS = 36;
    private static final DateTimeFormatter LOG_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CuratorshipRepository repository;
    private final StudyGroupRepository groupRepository;
    private final TeacherRepository teacherRepository;
    private final MonthScheduleService monthScheduleService;

    public List<Curatorship> findAll() {
        return repository.findAll();
    }

    public List<Curatorship> findByTeacherId(Long teacherId) {
        return repository.findByTeacherId(teacherId);
    }

    public List<Curatorship> findByGroupId(Long groupId) {
        return repository.findByGroupId(groupId);
    }

    public Optional<Curatorship> findById(Long id) {
        return repository.findById(id);
    }

    public Optional<Curatorship> findByTeacherIdAndGroupId(Long teacherId, Long groupId) {
        return repository.findByTeacherIdAndGroupId(teacherId, groupId);
    }

    // часы кураторства задаются в неделю а план на год это часы на учебные недели года
    public static double studyWeeks(LocalDate from, LocalDate to) {
        return com.karyakina.schedule.util.AcademicYearUtil.teachingDays(from, to) / 5.0;
    }

    public static double yearWeeks(int academicYear) {
        return studyWeeks(LocalDate.of(academicYear, 9, 1), LocalDate.of(academicYear + 1, 8, 31));
    }

    public static Map<Integer, Double> monthWeeks(int academicYear) {
        Map<Integer, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            LocalDate start = LocalDate.of(academicYear, 9, 1).plusMonths(i);
            double weeks = studyWeeks(start, start.withDayOfMonth(start.lengthOfMonth()));
            result.put(start.getMonthValue(), Math.round(weeks * 10) / 10.0);
        }
        return result;
    }

    public static double yearPlanHours(Curatorship c, int academicYear) {
        return (c.getHours() != null ? c.getHours() : 0) * yearWeeks(academicYear);
    }

    public static double doneHours(Curatorship c, int academicYear) {
        LocalDate from = LocalDate.of(academicYear, 9, 1);
        LocalDate to = LocalDate.of(academicYear + 1, 8, 31);
        double total = 0;
        for (CuratorEvent e : c.getEvents()) {
            if (e.getDate() == null || e.getDate().isBefore(from) || e.getDate().isAfter(to)) continue;
            total += e.getHours() != null ? e.getHours() : 0;
        }
        return total;
    }

    public Map<String, Object> overview(int academicYear) {
        Map<Long, Curatorship> byGroup = new HashMap<>();
        for (Curatorship c : repository.findAll()) byGroup.putIfAbsent(c.getGroup().getId(), c);

        List<Map<String, Object>> rows = new ArrayList<>();
        int withCurator = 0;
        double percentSum = 0;
        LocalDate from = LocalDate.of(academicYear, 9, 1);
        LocalDate to = LocalDate.of(academicYear + 1, 8, 31);
        List<StudyGroup> groups = new ArrayList<>(groupRepository.findAll());
        groups.sort(Comparator.comparing(g -> g.getName() == null ? "" : g.getName()));
        for (StudyGroup g : groups) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupId", g.getId());
            row.put("groupName", g.getName());
            row.put("course", g.getCourse());
            row.put("specialty", g.getSpecialty());
            row.put("studentCount", g.getStudentCount());
            Curatorship c = byGroup.get(g.getId());
            if (c != null) {
                withCurator++;
                long plan = Math.round(yearPlanHours(c, academicYear));
                double done = doneHours(c, academicYear);
                long events = c.getEvents().stream()
                        .filter(e -> e.getDate() != null && !e.getDate().isBefore(from) && !e.getDate().isAfter(to)).count();
                LocalDate last = c.getEvents().stream().map(CuratorEvent::getDate).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null);
                double percent = plan > 0 ? Math.min(100.0, done * 100.0 / plan) : 0;
                percentSum += percent;
                row.put("curatorshipId", c.getId());
                row.put("teacherId", c.getTeacher().getId());
                row.put("teacherName", c.getTeacher().getFullName());
                row.put("planHours", plan);
                row.put("hoursPerWeek", c.getHours());
                row.put("doneHours", done);
                row.put("percent", Math.round(percent));
                row.put("eventsCount", events);
                row.put("logsCount", c.getLogs().size());
                row.put("lastEventDate", last);
            }
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("academicYear", academicYear);
        result.put("totalGroups", groups.size());
        result.put("withCurator", withCurator);
        result.put("withoutCurator", groups.size() - withCurator);
        result.put("avgPercent", withCurator > 0 ? Math.round(percentSum / withCurator) : 0);
        result.put("rows", rows);
        return result;
    }

    public Map<String, Object> groupInfo(Curatorship c) {
        StudyGroup g = c.getGroup();
        LocalDate today = LocalDate.now();
        MonthScheduleDto upcoming = monthScheduleService.getRange(today, today.plusDays(20), null, null, g.getId(), false);

        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", g.getId());
        group.put("name", g.getName());
        group.put("course", g.getCourse());
        group.put("specialty", g.getSpecialty());
        group.put("studentCount", g.getStudentCount());

        List<Map<String, Object>> todayLessons = new ArrayList<>();
        int changes = 0;
        for (MonthLessonDto l : upcoming.lessons()) {
            if (l.substituted() || l.movedFrom() != null) changes++;
            if (!l.date().equals(today)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pairNumber", l.pairNumber());
            row.put("startTime", l.startTime());
            row.put("discipline", l.disciplineName());
            row.put("teacher", l.actualTeacherName());
            row.put("classroom", l.classroom());
            row.put("substituted", l.substituted());
            todayLessons.add(row);
        }
        List<MonthEventDto> events = upcoming.events().stream()
                .sorted(Comparator.comparing(MonthEventDto::startDate)).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", group);
        result.put("todayLessons", todayLessons);
        result.put("upcomingEvents", events);
        result.put("upcomingChanges", changes);
        return result;
    }

    @Transactional
    public Curatorship assign(Long teacherId, Long groupId, Integer hours) {
        if (teacherId == null || groupId == null) throw new IllegalArgumentException("Выберите преподавателя и группу");
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new IllegalArgumentException("Преподаватель не найден"));
        StudyGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Группа не найдена"));
        if (!repository.findByGroupId(groupId).isEmpty()) {
            throw new IllegalArgumentException("У группы " + group.getName() + " уже есть куратор");
        }
        Curatorship c = new Curatorship();
        c.setTeacher(teacher);
        c.setGroup(group);
        c.setHours(validHours(hours));
        c.setEvents(new ArrayList<>());
        c.setLogs(new ArrayList<>());
        return repository.save(c);
    }

    @Transactional
    public Curatorship update(Long id, Long teacherId, Integer hours) {
        Curatorship c = get(id);
        if (teacherId != null && !teacherId.equals(c.getTeacher().getId())) {
            c.setTeacher(teacherRepository.findById(teacherId)
                    .orElseThrow(() -> new IllegalArgumentException("Преподаватель не найден")));
        }
        if (hours != null) c.setHours(validHours(hours));
        return repository.save(c);
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    @Transactional
    public Curatorship saveEvent(Long id, Integer index, LocalDate date, Double hours, String type, String description) {
        Curatorship c = get(id);
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Опишите мероприятие");
        if (date == null) throw new IllegalArgumentException("Укажите дату");
        if (hours == null || hours < 0 || hours > 24) throw new IllegalArgumentException("Часы от 0 до 24");
        CuratorEvent event = CuratorEvent.builder()
                .date(date)
                .hours(hours)
                .type(type == null || type.isBlank() ? "Другое" : type.trim())
                .description(description.trim())
                .build();
        if (index == null) {
            c.getEvents().add(event);
        } else {
            if (index < 0 || index >= c.getEvents().size()) throw new IllegalArgumentException("Мероприятие не найдено");
            c.getEvents().set(index, event);
        }
        return repository.save(c);
    }

    @Transactional
    public Curatorship deleteEvent(Long id, int index) {
        Curatorship c = get(id);
        if (index >= 0 && index < c.getEvents().size()) {
            c.getEvents().remove(index);
            c = repository.save(c);
        }
        return c;
    }

    // запись журнала хранится как дата|текст
    @Transactional
    public Curatorship saveLog(Long id, Integer index, String text) {
        Curatorship c = get(id);
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Пустая запись");
        String clean = text.trim();
        if (index == null) {
            c.getLogs().add(LocalDate.now().format(LOG_DATE) + "|" + clean);
        } else {
            if (index < 0 || index >= c.getLogs().size()) throw new IllegalArgumentException("Запись не найдена");
            String old = c.getLogs().get(index);
            int bar = old.indexOf('|');
            String prefix = bar == 10 ? old.substring(0, 11) : "";
            c.getLogs().set(index, prefix + clean);
        }
        return repository.save(c);
    }

    @Transactional
    public Curatorship deleteLog(Long id, int index) {
        Curatorship c = get(id);
        if (index >= 0 && index < c.getLogs().size()) {
            c.getLogs().remove(index);
            c = repository.save(c);
        }
        return c;
    }

    private Curatorship get(Long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Кураторство не найдено"));
    }

    private static int validHours(Integer hours) {
        if (hours == null) return DEFAULT_HOURS;
        if (hours < 0 || hours > 168) throw new IllegalArgumentException("Часов в неделю от 0 до 168");
        return hours;
    }
}
