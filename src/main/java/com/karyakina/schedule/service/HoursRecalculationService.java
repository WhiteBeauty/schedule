package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.MonthlyRecord;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.SickLeave;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.domain.UnresolvedReschedule;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.MonthlyRecordRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.SickLeaveRepository;
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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Динамический пересчёт расписания по фактически вычитанным часам.
 *
 * Для каждой нагрузки семестра считается баланс:
 *   цель семестра − вычитано (readHours + пары, отданные на замену, + прошедшие, ещё не списанные пары)
 *   − часы будущих пар до конца семестра (с учётом всех отмен, переносов экзаменов и больничных).
 * Если часов не хватает — в свободные слоты календаря (без окон у группы) добавляются разовые пары,
 * равномерно до конца семестра. Если будущих пар больше, чем нужно, — снимаются последние пары семестра,
 * причём в первую очередь крайние в дне группы, чтобы не появлялись окна.
 *
 * Цель — план своего семестра: недочитанное осенью восполняется только осенью, весна считается отдельно.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HoursRecalculationService {

    private static final String ADDED_REASON = "Доп. пара: динамический пересчёт часов";
    private static final String UNRESOLVED_PREFIX = "Пересчёт часов: ";

    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;
    private final LessonInstanceRepository instanceRepository;
    private final SickLeaveRepository sickLeaveRepository;
    private final UnresolvedRescheduleRepository unresolvedRepository;
    private final LessonInstanceService lessonInstanceService;
    private final CalendarSlotFinder slotFinder;
    private final MonthlyRecordRepository monthlyRecordRepository;

    public record Change(Long loadId, String groupName, String disciplineName, String teacherName,
                         LocalDate date, int pairNumber, String classroom, String note) {
    }

    public record LoadBalance(Long loadId, String groupName, String disciplineName, String teacherName,
                              int targetHours, int deliveredHours, int futureHours, int balanceHours) {
    }

    public record Result(int academicYear, int semester, boolean dryRun, int loadsChecked,
                         List<LoadBalance> imbalances, List<Change> added, List<Change> cancelled,
                         List<String> unresolved, java.util.Set<Long> uncoveredLoadIds) {
    }

    private record Plan(TeacherLoad load, Long teacherId, int balance, List<Occurrence> future) {
    }

    @Transactional
    public Result recalculate(int academicYear, int semester, Collection<Long> onlyLoadIds, boolean dryRun) {
        Result first = recalculateOnce(academicYear, semester, onlyLoadIds, dryRun);
        if (dryRun || (first.added().isEmpty() && first.cancelled().isEmpty())) {
            return first;
        }
        // Снятие лишних пар у одних нагрузок освобождает место для других, а после снятия последней пары дня
        // последней становится следующая — поэтому повторяем, пока расписание не перестанет меняться.
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
            unresolved.add("Семестр " + semester + " уже закончился — пересчитывать нечего.");
            return new Result(academicYear, semester, dryRun, 0, imbalances, added, cancelled, unresolved,
                    new java.util.HashSet<>());
        }

        List<Schedule> all = scheduleRepository.findByAcademicYear(academicYear);
        Map<Long, List<Schedule>> byLoad = new HashMap<>();
        for (Schedule s : all) {
            if (s.getTeacherLoad() == null) continue;
            if (s.getSemester() != null && s.getSemester() != semester) continue;
            byLoad.computeIfAbsent(s.getTeacherLoad().getId(), k -> new ArrayList<>()).add(s);
        }
        Map<String, LessonInstance> instances = new HashMap<>();
        for (LessonInstance li : instanceRepository.findByLessonDateBetween(semStart, semEnd)) {
            if (li.getSchedule() != null) instances.put(li.getSchedule().getId() + "_" + li.getLessonDate(), li);
        }
        List<SickLeave> sickLeaves = sickLeaveRepository.findAll();
        CalendarSlotFinder.Calendar calendar = slotFinder.open(academicYear, today.isAfter(semStart) ? today : semStart, semEnd);

        int checked = 0;
        List<Plan> plans = new ArrayList<>();
        java.util.Set<Long> uncovered = new java.util.HashSet<>();
        java.util.Set<Long> balanced = new java.util.HashSet<>();
        for (Map.Entry<Long, List<Schedule>> entry : byLoad.entrySet()) {
            if (onlyLoadIds != null && !onlyLoadIds.isEmpty() && !onlyLoadIds.contains(entry.getKey())) continue;
            TeacherLoad load = entry.getValue().get(0).getTeacherLoad();
            if (Boolean.TRUE.equals(load.getOverload()) || load.getGroup() == null || load.getDiscipline() == null) continue;
            if (ScheduleGeneratorService.isPracticeOrDriving(load.getDiscipline().getName())) continue;
            boolean started = !today.isBefore(semStart);
            // Каждый семестр закрывается своими часами: недочитанное осенью добирается осенью,
            // на весну (другой семестр, другие пары) ничего не переносится.
            int target = semester == 2 ? nz(load.getSecondSemesterHours()) : nz(load.getFirstSemesterHours());
            if (target <= 0) continue;
            checked++;

            Long teacherId = load.getTeacher() != null ? load.getTeacher().getId() : null;
            int pendingPast = 0;
            int replaced = 0;
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
                    int hours = li != null && li.getDurationHours() != null ? li.getDurationHours() : durationHours(s);
                    // Замена: часы отдал другой преподаватель. Самостоятельная работа: администратор решил
                    // не восполнять пару. В обоих случаях группа этот объём закрыла — доставлять не нужно.
                    boolean past = d.isBefore(today) || (d.equals(today) && !s.getEndTime().isAfter(now.toLocalTime()));
                    if (status == LessonInstance.Status.REPLACED || status == LessonInstance.Status.INDEPENDENT_WORK) {
                        replaced += hours;
                        if (past) pastScheduled += hours;
                        continue;
                    }
                    if (status == LessonInstance.Status.CONFIRMED) {
                        if (past) pastScheduled += hours;
                        continue;
                    }
                    if (status != LessonInstance.Status.PLANNED) continue;
                    boolean absent = isAbsent(sickLeaves, teacherId, d);
                    if (past) {
                        if (!absent) {
                            pendingPast += hours;
                            pastScheduled += hours;
                        }
                    } else if (!absent) {
                        future += hours;
                        futureOccurrences.add(new Occurrence(s, d, hours));
                    }
                }
            }

            int read = started ? deliveredInSemester(load, semester, today) : 0;
            int delivered = read + replaced + pendingPast;
            // Защита от испорченных данных: «вычитано» не может превышать то, что вообще стояло в расписании
            // (весной — плюс осенний план). Такое бывает после сноса расписания: занятия удалены, часы остались.
            // В этом случае ничего не снимаем и не добавляем, а просим сверить часы с журналом.
            int possible = pastScheduled + 2;
            if (read > 0 && delivered > possible) {
                unresolved.add(load.getDiscipline().getName() + ", " + load.getGroup().getName() + ": вычитано "
                        + read + " ч, а по расписанию к сегодняшнему дню могло пройти не больше " + (possible - 2)
                        + " ч — пересчёт пропущен, сверьте «вычитано» с журналом.");
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

        // Сначала снимаем лишнее (это освобождает недельный лимит групп), потом добавляем недостающее.
        for (Plan plan : plans) {
            if (plan.balance() < 0) {
                int notRemoved = removePairs(plan.load(), (-plan.balance()) / 2, plan.future(), calendar, dryRun, cancelled);
                if (notRemoved > 0) {
                    unresolved.add(plan.load().getDiscipline().getName() + ", " + plan.load().getGroup().getName()
                            + ": лишние " + (notRemoved * 2) + " ч не сняты — эти пары стоят не последними в дне, "
                            + "снятие дало бы окно.");
                }
                balanced.add(plan.load().getId());
            }
        }
        for (Plan plan : plans) {
            if (plan.balance() > 0) {
                boolean covered = addPairs(plan.load(), plan.teacherId(), (plan.balance() + 1) / 2, today, semEnd,
                        semester, academicYear, calendar, dryRun, added, unresolved);
                if (covered) balanced.add(plan.load().getId());
                else uncovered.add(plan.load().getId());
            }
        }

        // Нагрузки, у которых часы теперь сходятся с планом, убираем из «облака» не вставших пар:
        // их часы покрыты (переносом, доп. парами или запасом расписания).
        if (!dryRun && !balanced.isEmpty()) {
            unresolvedRepository.findByAcademicYearOrderByOriginalDateAsc(academicYear).stream()
                    .filter(u -> u.getTeacherLoadId() != null && balanced.contains(u.getTeacherLoadId()))
                    .filter(u -> u.getOriginalDate() == null || AcademicYearUtil.semesterOfDate(u.getOriginalDate()) == semester)
                    .forEach(unresolvedRepository::delete);
        }
        return new Result(academicYear, semester, dryRun, checked, imbalances, added, cancelled, unresolved, uncovered);
    }

    private record Occurrence(Schedule schedule, LocalDate date, int hours) {
    }

    /**
     * Часы, вычитанные по нагрузке именно в этом семестре: проведённые часы по месяцам семестра
     * (сентябрь–декабрь или январь–июнь). Ручная правка «вычитано», не привязанная к месяцам,
     * относится к семестру, который идёт сейчас.
     */
    public int deliveredInSemester(TeacherLoad load, int semester, LocalDate today) {
        int inSemester = 0;
        int allMonths = 0;
        for (MonthlyRecord r : monthlyRecordRepository.findByTeacherLoadId(load.getId())) {
            int hours = r.getConductedHoursOrZero();
            allMonths += hours;
            int month = r.getMonth() == null ? 0 : r.getMonth();
            boolean monthInSemester = semester == 1 ? month >= 9 && month <= 12 : month >= 1 && month <= 6;
            if (monthInSemester) inSemester += hours;
        }
        int manualExtra = Math.max(0, nz(load.getReadHours()) - allMonths);
        if (manualExtra > 0 && AcademicYearUtil.semesterOfDate(today) == semester) {
            inSemester += manualExtra;
        }
        return inSemester;
    }

    public record ReadHoursFix(Long loadId, String groupName, String disciplineName, String teacherName,
                               int currentReadHours, int journalHours) {
    }

    public record RebuildResult(boolean applied, int loadsChanged, int currentTotal, int journalTotal,
                                List<ReadHoursFix> changes) {
    }

    /**
     * Восстановить «вычитано» (readHours и проведённые часы по месяцам) по журналу занятий.
     * Нужно, когда расписание сносили/пересоздавали: занятия удалялись, а вычитанные по ним часы оставались,
     * из-за чего нагрузка выглядела выполненной на сотни процентов. Ручные правки readHours при этом теряются,
     * поэтому сначала показывается предпросмотр.
     */
    @Transactional
    public RebuildResult rebuildReadHoursFromJournal(int academicYear, boolean apply) {
        Map<Long, Integer> total = new HashMap<>();
        Map<Long, Map<Integer, Integer>> perMonth = new HashMap<>();
        for (LessonInstance li : instanceRepository.findByAcademicYear(academicYear)) {
            if (li.getTeacherLoad() == null) continue;
            if (li.getStatus() != LessonInstance.Status.CONFIRMED && li.getStatus() != LessonInstance.Status.REPLACED) continue;
            int hours = li.getDurationHours() == null ? 0 : li.getDurationHours();
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
        long span = Math.max(1, ChronoUnit.DAYS.between(from, semEnd));
        int placed = 0;
        String lastFailure = null;
        String preferredRoom = scheduleRepository.findByTeacherLoadId(load.getId()).stream()
                .map(Schedule::getClassroom).filter(r -> r != null && !r.isBlank()).findFirst().orElse(null);
        for (int i = 0; i < pairs; i++) {
            LocalDate spreadFrom = from.plusDays(span * i / pairs);
            // В первую очередь недостающая пара закрывает существующее окно группы (после переносов/отмен).
            CalendarSlotFinder.Outcome outcome = calendar.find(new CalendarSlotFinder.Request(load, teacherId,
                    from, semEnd, semester, preferredRoom, -1, false, true));
            if (!outcome.found()) {
                outcome = calendar.find(new CalendarSlotFinder.Request(load, teacherId,
                        spreadFrom, semEnd, semester, preferredRoom, -1, false));
            }
            if (!outcome.found() && spreadFrom.isAfter(from)) {
                outcome = calendar.find(new CalendarSlotFinder.Request(load, teacherId, from, semEnd, semester,
                        preferredRoom, -1, false));
            }
            if (!outcome.found()) {
                // Без окна места нет — ставим с окном у группы, но с предупреждением, чтобы часы не потерялись.
                outcome = calendar.find(new CalendarSlotFinder.Request(load, teacherId, from, semEnd, semester,
                        preferredRoom, -1, true));
                if (outcome.found()) {
                    unresolved.add("Внимание: " + load.getDiscipline().getName() + ", " + load.getGroup().getName()
                            + " — доп. пара " + outcome.slot().date() + ", " + (outcome.slot().pairIdx() + 1)
                            + "-я пара: у группы в этот день будет окно (без окна места не нашлось).");
                }
            }
            if (!outcome.found()) {
                lastFailure = outcome.diagnostics().describe(CalendarSlotFinder.weekCap(load.getGroup()));
                continue;
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
            unresolvedRepository.findByAcademicYearOrderByOriginalDateAsc(academicYear).stream()
                    .filter(u -> u.getSpecialEventId() == null && load.getId().equals(u.getTeacherLoadId())
                            && u.getReason() != null && u.getReason().startsWith(UNRESOLVED_PREFIX))
                    .forEach(unresolvedRepository::delete);
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
                        .build());
            }
        }
        return missing <= 0;
    }

    private int removePairs(TeacherLoad load, int pairs, List<Occurrence> future,
                             CalendarSlotFinder.Calendar calendar, boolean dryRun, List<Change> cancelled) {
        List<Occurrence> candidates = new ArrayList<>(future);
        candidates.sort((a, b) -> {
            int c = b.date().compareTo(a.date());
            return c != 0 ? c : b.schedule().getStartTime().compareTo(a.schedule().getStartTime());
        });
        int removed = 0;
        // Снимаем только последнюю пару дня группы: из середины — будет окно, первую — день начнётся позже.
        // После снятия предыдущая пара того же дня становится последней, поэтому повторяем, пока есть прогресс.
        boolean progress = true;
        while (removed < pairs && progress) {
            progress = false;
            for (Occurrence o : new ArrayList<>(candidates)) {
                if (removed >= pairs) break;
                int pairIdx = GenerationGrid.pairIndex(o.schedule().getStartTime());
                TreeSet<Integer> groupPairs = calendar.pairsOfGroup(load.getGroup().getId(), o.date());
                if (!groupPairs.isEmpty() && pairIdx != groupPairs.last()) continue;
                String note = "Снято динамическим пересчётом: часы по плану семестра уже покрыты";
                if (!dryRun) {
                    lessonInstanceService.generateInstancesForDate(o.date(), o.schedule().getAcademicYear());
                    instanceRepository.findByScheduleIdAndLessonDate(o.schedule().getId(), o.date())
                            .ifPresent(li -> lessonInstanceService.cancelInstance(li.getId(), note,
                                    "system:recalculation"));
                }
                calendar.release(o.schedule().getId(), o.date());
                candidates.remove(o);
                removed++;
                progress = true;
                cancelled.add(new Change(load.getId(), load.getGroup().getName(), load.getDiscipline().getName(),
                        teacherName(load), o.date(), pairIdx + 1, o.schedule().getClassroom(), note));
            }
        }
        return pairs - removed;
    }

    private static boolean isAbsent(List<SickLeave> leaves, Long teacherId, LocalDate date) {
        if (teacherId == null) return false;
        return leaves.stream().anyMatch(s -> s.getTeacher() != null && teacherId.equals(s.getTeacher().getId())
                && !date.isBefore(s.getStartDate()) && !date.isAfter(s.getEndDate()));
    }

    private static int durationHours(Schedule s) {
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
