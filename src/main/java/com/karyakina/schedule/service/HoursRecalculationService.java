package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.MonthlyRecord;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.SickLeave;
import com.karyakina.schedule.domain.SpecialEvent;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.domain.UnresolvedReschedule;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.MonthlyRecordRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.SickLeaveRepository;
import com.karyakina.schedule.repository.SpecialEventRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.repository.UnresolvedRescheduleRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.util.AcademicYearUtil;
import com.karyakina.schedule.util.ScheduleOccurrence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoursRecalculationService {

    private static final String ADDED_REASON = "Доп. пара: пересчёт часов";
    private static final String UNRESOLVED_PREFIX = "Пересчёт часов: ";
    private static final int MAX_SAME_LOAD_PER_DAY = 2;

    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;
    private final LessonInstanceRepository instanceRepository;
    private final SickLeaveRepository sickLeaveRepository;
    private final SpecialEventRepository specialEventRepository;
    private final UnresolvedRescheduleRepository unresolvedRepository;
    private final LessonInstanceService lessonInstanceService;
    private final CalendarSlotFinder slotFinder;
    private final MonthlyRecordRepository monthlyRecordRepository;
    private final HourAccountingService hourAccountingService;
    private final GroupWindowService groupWindowService;
    private final MonthlyRecordService monthlyRecordService;

    public record Change(Long loadId, String groupName, String disciplineName, String teacherName,
                         LocalDate date, int pairNumber, String classroom, String note) {
    }

    public record LoadBalance(Long loadId, String groupName, String disciplineName, String teacherName,
                              int targetHours, int deliveredHours, int futureHours, int balanceHours) {
    }

    public record Result(int academicYear, int semester, boolean dryRun, int loadsChecked,
                         List<LoadBalance> imbalances, List<Change> added, List<Change> cancelled,
                         List<String> unresolved, Set<Long> uncoveredLoadIds) {
    }

    private record Plan(TeacherLoad load, Long teacherId, int balance, List<Occurrence> future) {
    }

    private record Occurrence(Schedule schedule, LocalDate date, int hours) {
    }

    @Transactional
    public Result recalculate(int academicYear, int semester, Collection<Long> onlyLoadIds, boolean dryRun) {
        Result first = recalculateOnce(academicYear, semester, onlyLoadIds, dryRun);
        if (dryRun || (first.added().isEmpty() && first.cancelled().isEmpty())) {
            return first;
        }
        List<Change> added = new ArrayList<>(first.added());
        List<Change> cancelled = new ArrayList<>(first.cancelled());
        Result last = first;
        for (int round = 0; round < 4; round++) {
            Result next = recalculateOnce(academicYear, semester, onlyLoadIds, false);
            added.addAll(next.added());
            cancelled.addAll(next.cancelled());
            last = next;
            if (next.added().isEmpty() && next.cancelled().isEmpty()) break;
        }
        Set<Long> touched = new HashSet<>();
        added.forEach(c -> touched.add(c.loadId()));
        cancelled.forEach(c -> touched.add(c.loadId()));
        touched.forEach(monthlyRecordService::recalculateHoursForLoad);
        log.info("Пересчёт по часам (год {}, семестр {}, нагрузок {}): добавлено пар {}, снято {}, требует внимания {}",
                academicYear, semester, onlyLoadIds == null || onlyLoadIds.isEmpty() ? "все" : onlyLoadIds.size(),
                added.size(), cancelled.size(), last.unresolved().size());
        last.unresolved().forEach(u -> log.info("Пересчёт по часам: {}", u));
        return new Result(academicYear, semester, false, first.loadsChecked(), first.imbalances(), added, cancelled,
                last.unresolved(), last.uncoveredLoadIds());
    }

    private Result recalculateOnce(int academicYear, int semester, Collection<Long> onlyLoadIds, boolean dryRun) {
        LocalDate semStart = AcademicYearUtil.semesterStart(semester, academicYear);
        LocalDate semEnd = AcademicYearUtil.semesterEnd(semester, academicYear);
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        List<LoadBalance> imbalances = new ArrayList<>();
        List<Change> added = new ArrayList<>();
        List<Change> cancelled = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        if (today.isAfter(semEnd)) {
            unresolved.add("Семестр " + semester + " уже закончился, пересчитывать нечего.");
            return new Result(academicYear, semester, dryRun, 0, imbalances, added, cancelled, unresolved, new HashSet<>());
        }

        Map<Long, List<Schedule>> byLoad = new HashMap<>();
        for (Schedule s : scheduleRepository.findByAcademicYear(academicYear)) {
            if (s.getTeacherLoad() == null) continue;
            if (s.getSemester() != null && s.getSemester() != semester) continue;
            byLoad.computeIfAbsent(s.getTeacherLoad().getId(), k -> new ArrayList<>()).add(s);
        }
        Map<String, LessonInstance> instances = new HashMap<>();
        Map<Long, Integer> foreignCredited = new HashMap<>();
        for (LessonInstance li : instanceRepository.findByLessonDateBetween(semStart, semEnd)) {
            if (li.getSchedule() == null) continue;
            instances.put(li.getSchedule().getId() + "_" + li.getLessonDate(), li);
            TeacherLoad slotLoad = li.getSchedule().getTeacherLoad();
            if (li.getTeacherLoad() != null && slotLoad != null && !li.getTeacherLoad().getId().equals(slotLoad.getId())
                    && LessonInstanceService.isCredited(li)) {
                foreignCredited.merge(li.getTeacherLoad().getId(), nz(li.getDurationHours()), Integer::sum);
            }
        }
        List<SickLeave> sickLeaves = sickLeaveRepository.findAll();
        Map<Long, List<SpecialEvent>> eventsByGroup = new HashMap<>();
        for (SpecialEvent e : specialEventRepository.findByAcademicYear(academicYear)) {
            if (e.getGroup() != null) eventsByGroup.computeIfAbsent(e.getGroup().getId(), k -> new ArrayList<>()).add(e);
        }
        CalendarSlotFinder.Calendar calendar = slotFinder.open(academicYear, today.isAfter(semStart) ? today : semStart, semEnd);

        int checked = 0;
        List<Plan> plans = new ArrayList<>();
        Set<Long> uncovered = new HashSet<>();
        Set<Long> balanced = new HashSet<>();
        for (Map.Entry<Long, List<Schedule>> entry : byLoad.entrySet()) {
            if (onlyLoadIds != null && !onlyLoadIds.isEmpty() && !onlyLoadIds.contains(entry.getKey())) continue;
            TeacherLoad load = entry.getValue().get(0).getTeacherLoad();
            if (Boolean.TRUE.equals(load.getOverload()) || load.getGroup() == null || load.getDiscipline() == null) continue;
            if (ScheduleGeneratorService.isPracticeOrDriving(load.getDiscipline().getName())) continue;
            int target = semester == 2 ? nz(load.getSecondSemesterHours()) : nz(load.getFirstSemesterHours());
            if (target <= 0) continue;
            checked++;

            Long teacherId = load.getTeacher() != null ? load.getTeacher().getId() : null;
            List<SpecialEvent> groupEvents = eventsByGroup.getOrDefault(load.getGroup().getId(), List.of());
            int pendingPast = 0;
            int covered = 0;
            int future = 0;
            int pastScheduled = 0;
            List<Occurrence> futureOccurrences = new ArrayList<>();
            for (LocalDate d = semStart; !d.isAfter(semEnd); d = d.plusDays(1)) {
                if (!AcademicYearUtil.isWorkDay(d) || AcademicYearUtil.isVacation(d)) continue;
                int week = AcademicYearUtil.academicWeek(d, academicYear);
                for (Schedule s : entry.getValue()) {
                    if (!ScheduleOccurrence.occursOn(s, d, week, semester)) continue;
                    LessonInstance li = instances.get(s.getId() + "_" + d);
                    LessonInstance.Status status = li == null ? LessonInstance.Status.PLANNED : li.getStatus();
                    int hours = li != null && li.getDurationHours() != null ? li.getDurationHours() : pairHours(s);
                    boolean past = d.isBefore(today) || (d.equals(today) && !s.getEndTime().isAfter(now.toLocalTime()));
                    if (status == LessonInstance.Status.REPLACED || status == LessonInstance.Status.INDEPENDENT_WORK) {
                        boolean ownCredit = li.getTeacherLoad() != null && li.getTeacherLoad().getId().equals(load.getId())
                                && LessonInstanceService.isCredited(li);
                        if (!ownCredit) covered += hours;
                        if (past) pastScheduled += hours;
                        continue;
                    }
                    if (status == LessonInstance.Status.CONFIRMED) {
                        if (past) pastScheduled += hours;
                        continue;
                    }
                    if (status != LessonInstance.Status.PLANNED) continue;
                    if (blockedByEvent(groupEvents, load, d) || isAbsent(sickLeaves, teacherId, d)) continue;
                    if (past) {
                        pendingPast += hours;
                        pastScheduled += hours;
                    } else {
                        future += hours;
                        futureOccurrences.add(new Occurrence(s, d, hours));
                    }
                }
            }

            int read = hourAccountingService.conductedInSemester(load, semester, today,
                    monthlyRecordRepository.findByTeacherLoadId(load.getId()))
                    - foreignCredited.getOrDefault(load.getId(), 0);
            read = Math.max(0, read);
            int delivered = read + covered + pendingPast;
            int possible = pastScheduled + 2;
            if (read > 0 && delivered > possible && read > pastScheduled) {
                unresolved.add(load.getDiscipline().getName() + ", " + load.getGroup().getName() + ": вычитано "
                        + read + " ч, а по расписанию к сегодняшнему дню могло пройти не больше " + pastScheduled
                        + " ч, пересчёт пропущен, сверьте вычитанное с журналом.");
                uncovered.add(load.getId());
                continue;
            }
            int balance = target - delivered - future;
            if (Math.abs(balance) < 2) {
                balanced.add(load.getId());
                continue;
            }
            imbalances.add(new LoadBalance(load.getId(), load.getGroup().getName(), load.getDiscipline().getName(),
                    teacherName(load), target, delivered, future, balance));
            plans.add(new Plan(load, teacherId, balance, futureOccurrences));
        }

        // сначала снимаем лишнее у тех кто отчитал потом ставим недостающее
        Map<Long, Set<LocalDate>> holes = new HashMap<>();
        Set<LocalDate> materialized = new HashSet<>();
        for (Plan plan : plans) {
            if (plan.balance() < 0) {
                removePairs(plan.load(), (-plan.balance()) / 2, plan.future(), calendar, dryRun, cancelled, holes,
                        materialized);
                balanced.add(plan.load().getId());
            }
        }
        plans.sort((a, b) -> Integer.compare(b.balance(), a.balance()));
        for (Plan plan : plans) {
            if (plan.balance() > 0) {
                boolean ok = addPairs(plan.load(), plan.teacherId(), (plan.balance() + 1) / 2, today, semEnd,
                        semester, academicYear, calendar, dryRun, added, unresolved);
                if (ok) balanced.add(plan.load().getId());
                else uncovered.add(plan.load().getId());
            }
        }
        if (!dryRun && !holes.isEmpty()) {
            GroupWindowService.Outcome windows = groupWindowService.closeWindows(holes, academicYear, null);
            unresolved.addAll(windows.warnings());
        }

        if (!dryRun && !balanced.isEmpty()) {
            dropUnresolved(academicYear, u -> u.getTeacherLoadId() != null && balanced.contains(u.getTeacherLoadId())
                    && u.belongsTo(semester));
        }
        return new Result(academicYear, semester, dryRun, checked, imbalances, added, cancelled, unresolved, uncovered);
    }

    private static boolean blockedByEvent(List<SpecialEvent> events, TeacherLoad load, LocalDate date) {
        for (SpecialEvent e : events) {
            if (date.isBefore(e.getStartDate()) || date.isAfter(e.getEndDate())) continue;
            if (e.getTeacherLoad() != null && e.getTeacherLoad().getId().equals(load.getId())) continue;
            return true;
        }
        return false;
    }

    public record ReadHoursFix(Long loadId, String groupName, String disciplineName, String teacherName,
                               int currentReadHours, int journalHours) {
    }

    public record RebuildResult(boolean applied, int loadsChanged, int currentTotal, int journalTotal,
                                List<ReadHoursFix> changes) {
    }

    @Transactional
    public RebuildResult rebuildReadHoursFromJournal(int academicYear, boolean apply) {
        Map<Long, Integer> total = new HashMap<>();
        Map<Long, Map<Integer, Integer>> perMonth = new HashMap<>();
        for (LessonInstance li : instanceRepository.findByAcademicYear(academicYear)) {
            if (li.getTeacherLoad() == null || !LessonInstanceService.isCredited(li)) continue;
            int hours = nz(li.getDurationHours());
            total.merge(li.getTeacherLoad().getId(), hours, Integer::sum);
            perMonth.computeIfAbsent(li.getTeacherLoad().getId(), k -> new HashMap<>())
                    .merge(li.getLessonDate().getMonthValue(), hours, Integer::sum);
        }
        List<ReadHoursFix> changes = new ArrayList<>();
        int currentTotal = 0;
        int journalTotal = 0;
        for (TeacherLoad load : loadRepository.findByAcademicYear(academicYear)) {
            int current = nz(load.getReadHours());
            int journal = total.getOrDefault(load.getId(), 0);
            currentTotal += current;
            journalTotal += journal;
            if (current == journal) continue;
            changes.add(new ReadHoursFix(load.getId(),
                    load.getGroup() != null ? load.getGroup().getName() : "—",
                    load.getDiscipline() != null ? load.getDiscipline().getName() : "—",
                    teacherName(load), current, journal));
            if (apply) {
                load.setReadHours(journal);
                loadRepository.save(load);
                Map<Integer, Integer> months = perMonth.getOrDefault(load.getId(), Map.of());
                for (MonthlyRecord record : monthlyRecordRepository.findByTeacherLoadId(load.getId())) {
                    if (record.getYear() != null && !record.getYear().equals(load.getAcademicYear())) continue;
                    record.setConductedHours(months.getOrDefault(record.getMonth(), 0));
                    record.setAdjustedHours(null);
                    record.setChangedBy("system:rebuild-from-journal");
                    monthlyRecordRepository.save(record);
                }
            }
        }
        return new RebuildResult(apply, changes.size(), currentTotal, journalTotal, changes);
    }

    private boolean addPairs(TeacherLoad load, Long teacherId, int pairs, LocalDate from, LocalDate semEnd,
                             int semester, int academicYear, CalendarSlotFinder.Calendar calendar, boolean dryRun,
                             List<Change> added, List<String> unresolved) {
        int placed = 0;
        String lastFailure = null;
        String preferredRoom = scheduleRepository.findByTeacherLoadId(load.getId()).stream()
                .map(Schedule::getClassroom).filter(r -> r != null && !r.isBlank()).findFirst().orElse(null);
        for (int i = 0; i < pairs; i++) {
            // ставим ближе к концу семестра
            CalendarSlotFinder.Outcome outcome = calendar.find(new CalendarSlotFinder.Request(load, teacherId,
                    from, semEnd, semester, preferredRoom, -1, false, true, true, MAX_SAME_LOAD_PER_DAY));
            if (!outcome.found()) {
                outcome = calendar.find(new CalendarSlotFinder.Request(load, teacherId,
                        from, semEnd, semester, preferredRoom, -1, false, false, true, MAX_SAME_LOAD_PER_DAY));
            }
            if (!outcome.found()) {
                outcome = calendar.find(new CalendarSlotFinder.Request(load, teacherId,
                        from, semEnd, semester, preferredRoom, -1, true, false, true, MAX_SAME_LOAD_PER_DAY));
                if (outcome.found()) {
                    unresolved.add("Внимание: " + load.getDiscipline().getName() + ", " + load.getGroup().getName()
                            + " доп. пара " + outcome.slot().date() + ", " + (outcome.slot().pairIdx() + 1)
                            + "-я пара, у группы в этот день будет окно (без окна места не нашлось).");
                }
            }
            if (!outcome.found()) {
                lastFailure = outcome.diagnostics().describe(CalendarSlotFinder.weekCap(load.getGroup()));
                break;
            }
            CalendarSlotFinder.Slot slot = outcome.slot();
            Long scheduleId = null;
            if (!dryRun) {
                Schedule row = scheduleRepository.save(Schedule.builder()
                        .teacherLoad(load)
                        .dayOfWeek(slot.date().getDayOfWeek())
                        .startTime(GenerationGrid.start(slot.pairIdx()))
                        .endTime(GenerationGrid.end(slot.pairIdx()))
                        .classroom(slot.room())
                        .academicWeek(slot.week())
                        .academicYear(academicYear)
                        .semester(semester)
                        .rescheduledReason(ADDED_REASON)
                        .build());
                scheduleId = row.getId();
            }
            calendar.occupy(slot, load, teacherId, scheduleId);
            placed++;
            added.add(new Change(load.getId(), load.getGroup().getName(), load.getDiscipline().getName(),
                    teacherName(load), slot.date(), slot.pairIdx() + 1, slot.room(), ADDED_REASON));
        }

        if (!dryRun) {
            dropUnresolved(academicYear, u -> u.getSpecialEventId() == null && load.getId().equals(u.getTeacherLoadId())
                    && u.belongsTo(semester) && u.getReason() != null
                    && (u.getReason().startsWith(UNRESOLVED_PREFIX) || u.getReason().startsWith("Автосоставление")));
        }
        int missing = pairs - placed;
        if (missing > 0) {
            String reason = UNRESOLVED_PREFIX + "не хватает " + (missing * 2) + " ч до плана семестра, "
                    + "свободных слотов до конца семестра нет"
                    + (lastFailure != null ? " (" + lastFailure + ")" : "") + ".";
            unresolved.add(load.getDiscipline().getName() + ", " + load.getGroup().getName() + ": " + reason);
            if (!dryRun) {
                unresolvedRepository.save(UnresolvedReschedule.builder()
                        .teacherLoadId(load.getId())
                        .groupId(load.getGroup().getId())
                        .groupName(load.getGroup().getName())
                        .disciplineName(load.getDiscipline().getName())
                        .teacherName(teacherName(load))
                        .hours(missing * 2)
                        .reason(reason)
                        .academicYear(academicYear)
                        .semester(semester)
                        .build());
            }
        }
        return missing <= 0;
    }

    // пачкой по id чтобы не падать если запись уже убрали из окна не вставших пар
    private void dropUnresolved(int academicYear, java.util.function.Predicate<UnresolvedReschedule> filter) {
        List<Long> ids = unresolvedRepository.findByAcademicYearOrderByOriginalDateAsc(academicYear).stream()
                .filter(filter).map(UnresolvedReschedule::getId).toList();
        if (!ids.isEmpty()) {
            unresolvedRepository.deleteAllByIdInBatch(ids);
        }
    }

    private void removePairs(TeacherLoad load, int pairs, List<Occurrence> future, CalendarSlotFinder.Calendar calendar,
                             boolean dryRun, List<Change> cancelled, Map<Long, Set<LocalDate>> holes,
                             Set<LocalDate> materialized) {
        List<Occurrence> candidates = new ArrayList<>(future);
        candidates.sort((a, b) -> {
            int c = b.date().compareTo(a.date());
            return c != 0 ? c : b.schedule().getStartTime().compareTo(a.schedule().getStartTime());
        });
        int removed = 0;
        boolean progress = true;
        while (removed < pairs && progress) {
            progress = false;
            for (Occurrence o : new ArrayList<>(candidates)) {
                if (removed >= pairs) break;
                int pairIdx = GenerationGrid.pairIndex(o.schedule().getStartTime());
                TreeSet<Integer> groupPairs = calendar.pairsOfGroup(load.getGroup().getId(), o.date());
                if (!groupPairs.isEmpty() && pairIdx != groupPairs.last()) continue;
                cancelOccurrence(load, o, calendar, dryRun, cancelled, materialized);
                candidates.remove(o);
                removed++;
                progress = true;
            }
        }
        // часы кончились снимаем и из середины дня а окно потом закроем
        for (Occurrence o : new ArrayList<>(candidates)) {
            if (removed >= pairs) break;
            cancelOccurrence(load, o, calendar, dryRun, cancelled, materialized);
            holes.computeIfAbsent(load.getGroup().getId(), k -> new TreeSet<>()).add(o.date());
            removed++;
        }
    }

    private void cancelOccurrence(TeacherLoad load, Occurrence o, CalendarSlotFinder.Calendar calendar, boolean dryRun,
                                  List<Change> cancelled, Set<LocalDate> materialized) {
        String note = "Снято пересчётом: часы по плану семестра уже вычитаны";
        if (!dryRun) {
            if (materialized.add(o.date())) {
                lessonInstanceService.generateInstancesForDate(o.date(), o.schedule().getAcademicYear());
            }
            instanceRepository.findByScheduleIdAndLessonDate(o.schedule().getId(), o.date())
                    .ifPresent(li -> lessonInstanceService.cancelInstance(li.getId(), note, "system:recalculation"));
        }
        calendar.release(o.schedule().getId(), o.date());
        cancelled.add(new Change(load.getId(), load.getGroup().getName(), load.getDiscipline().getName(),
                teacherName(load), o.date(), GenerationGrid.pairIndex(o.schedule().getStartTime()) + 1,
                o.schedule().getClassroom(), note));
    }

    private static boolean isAbsent(List<SickLeave> leaves, Long teacherId, LocalDate date) {
        if (teacherId == null) return false;
        return leaves.stream().anyMatch(s -> s.getTeacher() != null && teacherId.equals(s.getTeacher().getId())
                && !date.isBefore(s.getStartDate()) && !date.isAfter(s.getEndDate()));
    }

    private static int pairHours(Schedule s) {
        long minutes = ChronoUnit.MINUTES.between(s.getStartTime(), s.getEndTime());
        return (int) Math.max(1, Math.round(minutes / 60.0));
    }

    private static String teacherName(TeacherLoad load) {
        return load.getTeacher() != null ? load.getTeacher().getFullName() : "Не назначен";
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
