package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.SickLeave;
import com.karyakina.schedule.domain.SpecialEvent;
import com.karyakina.schedule.domain.StudyGroup;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.ClassroomRepository;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.SickLeaveRepository;
import com.karyakina.schedule.repository.SpecialEventRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.util.AcademicYearUtil;
import com.karyakina.schedule.util.ScheduleOccurrence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Поиск свободного места для пары в реальном календаре (конкретная дата + номер пары), а не в недельном шаблоне.
 *
 * Учитывает: семестр и активные недели строк расписания, отменённые занятия (их слот считается свободным),
 * фактического преподавателя при замене, праздники и каникулы, экзамены/практики группы, больничные преподавателя,
 * обед, лимиты группы на день и неделю, вместимость и профиль аудиторий. По умолчанию не допускает окон у группы:
 * пара ставится либо в существующее окно, либо вплотную к парам группы этого дня, либо в пустой день.
 */
@Service
@RequiredArgsConstructor
public class CalendarSlotFinder {

    public static final int DEFAULT_GROUP_WEEK_CAP = 18;
    public static final int DEFAULT_GROUP_DAY_CAP = 5;

    private final ScheduleRepository scheduleRepository;
    private final LessonInstanceRepository instanceRepository;
    private final SpecialEventRepository specialEventRepository;
    private final SickLeaveRepository sickLeaveRepository;
    private final ClassroomRepository classroomRepository;
    private final SettingsService settingsService;

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Calendar open(int academicYear, LocalDate from, LocalDate to) {
        LocalDate start = from.with(DayOfWeek.MONDAY);
        LocalDate end = to.with(DayOfWeek.SUNDAY);
        LocalTime[] globalLunch = null;
        try {
            globalLunch = settingsService.getGlobalLunchWindow();
        } catch (Exception ignored) {
            // обед не настроен — ограничений нет
        }
        return new Calendar(academicYear, start, end,
                scheduleRepository.findByAcademicYear(academicYear),
                instanceRepository.findByLessonDateBetween(start, end),
                specialEventRepository.findByAcademicYear(academicYear),
                sickLeaveRepository.findAll(),
                GenerationGrid.rooms(classroomRepository.findAll()),
                globalLunch);
    }

    public static int weekCap(StudyGroup group) {
        return group != null && group.getMaxWeeklyPairs() != null && group.getMaxWeeklyPairs() > 0
                ? group.getMaxWeeklyPairs() : DEFAULT_GROUP_WEEK_CAP;
    }

    public record Request(TeacherLoad load, Long teacherId, LocalDate from, LocalDate to, int semester,
                          String preferredRoom, int preferredPair, boolean allowWindows, boolean onlyFillGaps) {
        public Request(TeacherLoad load, Long teacherId, LocalDate from, LocalDate to, int semester,
                       String preferredRoom, int preferredPair, boolean allowWindows) {
            this(load, teacherId, from, to, semester, preferredRoom, preferredPair, allowWindows, false);
        }
    }

    public record Slot(LocalDate date, int pairIdx, String room, int week, boolean createsWindow) {
    }

    public record Outcome(Slot slot, Diagnostics diagnostics) {
        public boolean found() {
            return slot != null;
        }
    }

    public static final class Diagnostics {
        int blockedByEvent;
        int teacherSick;
        int weekCap;
        int dayCap;
        int teacherBusy;
        int groupBusy;
        int noRoom;
        int wouldCreateWindow;
        int lunch;
        int daysChecked;

        public String describe(int groupWeekCap) {
            List<String> parts = new ArrayList<>();
            if (teacherBusy > 0) parts.add("преподаватель занят (" + teacherBusy + " слотов)");
            if (groupBusy > 0) parts.add("у группы уже пара в это время (" + groupBusy + " слотов)");
            if (wouldCreateWindow > 0) parts.add("слот создал бы окно у группы (" + wouldCreateWindow + ")");
            if (noRoom > 0) parts.add("нет подходящей свободной аудитории (" + noRoom + " слотов)");
            if (lunch > 0) parts.add("обеденный перерыв (" + lunch + ")");
            if (weekCap > 0) parts.add("у группы выбран лимит " + groupWeekCap + " пар/нед (" + weekCap + " дней)");
            if (dayCap > 0) parts.add("у группы уже максимум пар в день (" + dayCap + " дней)");
            if (blockedByEvent > 0) parts.add("день занят экзаменом/практикой группы (" + blockedByEvent + " дней)");
            if (teacherSick > 0) parts.add("преподаватель отсутствует (" + teacherSick + " дней)");
            if (parts.isEmpty()) {
                return daysChecked == 0
                        ? "до конца семестра не осталось учебных дней"
                        : "свободного слота не нашлось";
            }
            return String.join("; ", parts);
        }
    }

    static final class Occ {
        final Long scheduleId;
        final Long loadId;
        final Long groupId;
        final Long teacherId;
        final String room;
        final int pairIdx;

        Occ(Long scheduleId, Long loadId, Long groupId, Long teacherId, String room, int pairIdx) {
            this.scheduleId = scheduleId;
            this.loadId = loadId;
            this.groupId = groupId;
            this.teacherId = teacherId;
            this.room = room;
            this.pairIdx = pairIdx;
        }
    }

    public static final class Calendar {
        private final int academicYear;
        private final LocalDate from;
        private final LocalDate to;
        private final Map<LocalDate, List<Occ>> byDate = new HashMap<>();
        private final Map<Long, List<SpecialEvent>> eventsByGroup = new HashMap<>();
        private final List<SickLeave> sickLeaves;
        private final List<GenerationGrid.Room> rooms;
        private final LocalTime[] globalLunch;
        private final Map<Long, Teacher> teachers = new HashMap<>();

        Calendar(int academicYear, LocalDate from, LocalDate to, List<Schedule> schedules,
                 List<LessonInstance> instances, List<SpecialEvent> events, List<SickLeave> sickLeaves,
                 List<GenerationGrid.Room> rooms, LocalTime[] globalLunch) {
            this.academicYear = academicYear;
            this.from = from;
            this.to = to;
            this.sickLeaves = sickLeaves;
            this.rooms = new ArrayList<>(rooms);
            this.rooms.sort(Comparator.comparingInt(GenerationGrid.Room::capacity));
            this.globalLunch = globalLunch;
            for (SpecialEvent e : events) {
                if (e.getGroup() != null) {
                    eventsByGroup.computeIfAbsent(e.getGroup().getId(), k -> new ArrayList<>()).add(e);
                }
            }
            Map<String, LessonInstance> instanceByKey = new HashMap<>();
            for (LessonInstance li : instances) {
                if (li.getSchedule() != null) {
                    instanceByKey.put(li.getSchedule().getId() + "_" + li.getLessonDate(), li);
                }
            }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (!AcademicYearUtil.isWorkDay(d) || AcademicYearUtil.isVacation(d)) continue;
                int week = AcademicYearUtil.academicWeek(d, academicYear);
                int semester = AcademicYearUtil.semesterOfDate(d);
                List<Occ> day = new ArrayList<>();
                for (Schedule s : schedules) {
                    TeacherLoad load = s.getTeacherLoad();
                    if (load == null || !ScheduleOccurrence.occursOn(s, d, week, semester)) continue;
                    LessonInstance li = instanceByKey.get(s.getId() + "_" + d);
                    if (li != null && (li.getStatus() == LessonInstance.Status.CANCELLED
                            || li.getStatus() == LessonInstance.Status.INDEPENDENT_WORK)) {
                        continue;
                    }
                    Long teacherId = li != null && li.getActualTeacher() != null
                            ? li.getActualTeacher().getId()
                            : (load.getTeacher() != null ? load.getTeacher().getId() : null);
                    if (load.getTeacher() != null) {
                        teachers.putIfAbsent(load.getTeacher().getId(), load.getTeacher());
                    }
                    for (int pairIdx : pairIndexes(s)) {
                        day.add(new Occ(s.getId(), load.getId(),
                                load.getGroup() != null ? load.getGroup().getId() : null,
                                teacherId, s.getClassroom(), pairIdx));
                    }
                }
                byDate.put(d, day);
            }
        }

        private static List<Integer> pairIndexes(Schedule s) {
            int exact = GenerationGrid.pairIndex(s.getStartTime());
            if (exact >= 0) return List.of(exact);
            List<Integer> result = new ArrayList<>();
            if (s.getStartTime() == null || s.getEndTime() == null) return result;
            for (int p = 0; p < GenerationGrid.pairsPerDay(); p++) {
                if (s.getStartTime().isBefore(GenerationGrid.end(p)) && GenerationGrid.start(p).isBefore(s.getEndTime())) {
                    result.add(p);
                }
            }
            return result;
        }

        public int academicYear() {
            return academicYear;
        }

        /** Освободить слот отменённого занятия (например, при экзамене в этот день). */
        public void release(Long scheduleId, LocalDate date) {
            List<Occ> day = byDate.get(date);
            if (day != null) {
                day.removeIf(o -> o.scheduleId != null && o.scheduleId.equals(scheduleId));
            }
        }

        public void occupy(Slot slot, TeacherLoad load, Long teacherId, Long scheduleId) {
            byDate.computeIfAbsent(slot.date(), k -> new ArrayList<>()).add(new Occ(scheduleId, load.getId(),
                    load.getGroup() != null ? load.getGroup().getId() : null, teacherId, slot.room(), slot.pairIdx()));
        }

        public boolean isGroupBlocked(Long groupId, LocalDate date) {
            return eventsByGroup.getOrDefault(groupId, List.of()).stream()
                    .anyMatch(e -> !date.isBefore(e.getStartDate()) && !date.isAfter(e.getEndDate()));
        }

        public boolean isTeacherAbsent(Long teacherId, LocalDate date) {
            if (teacherId == null) return false;
            return sickLeaves.stream().anyMatch(s -> s.getTeacher() != null && teacherId.equals(s.getTeacher().getId())
                    && !date.isBefore(s.getStartDate()) && !date.isAfter(s.getEndDate()));
        }

        public int groupWeekCount(Long groupId, LocalDate date) {
            LocalDate monday = date.with(DayOfWeek.MONDAY);
            int count = 0;
            for (int i = 0; i < 7; i++) {
                for (Occ o : byDate.getOrDefault(monday.plusDays(i), List.of())) {
                    if (groupId.equals(o.groupId)) count++;
                }
            }
            return count;
        }

        public TreeSet<Integer> groupPairs(Long groupId, LocalDate date) {
            TreeSet<Integer> result = new TreeSet<>();
            for (Occ o : byDate.getOrDefault(date, List.of())) {
                if (groupId.equals(o.groupId)) result.add(o.pairIdx);
            }
            return result;
        }

        public boolean isTeacherBusy(Long teacherId, LocalDate date, int pairIdx) {
            if (teacherId == null) return false;
            return byDate.getOrDefault(date, List.of()).stream()
                    .anyMatch(o -> teacherId.equals(o.teacherId) && o.pairIdx == pairIdx);
        }

        public boolean isRoomBusy(String room, LocalDate date, int pairIdx) {
            return byDate.getOrDefault(date, List.of()).stream()
                    .anyMatch(o -> room.equals(o.room) && o.pairIdx == pairIdx);
        }

        private int teacherDayCount(Long teacherId, LocalDate date) {
            if (teacherId == null) return 0;
            return (int) byDate.getOrDefault(date, List.of()).stream()
                    .filter(o -> teacherId.equals(o.teacherId)).map(o -> o.pairIdx).distinct().count();
        }

        public static boolean createsWindow(TreeSet<Integer> groupPairs, int pairIdx) {
            if (groupPairs.isEmpty()) return false;
            int min = groupPairs.first();
            int max = groupPairs.last();
            if (pairIdx > min && pairIdx < max) return false;
            return pairIdx < min - 1 || pairIdx > max + 1;
        }

        private LocalTime[] lunchFor(StudyGroup group) {
            if (group != null && group.getLunchStart() != null && group.getLunchEnd() != null) {
                return new LocalTime[]{group.getLunchStart(), group.getLunchEnd()};
            }
            return globalLunch;
        }

        public Outcome find(Request req) {
            Outcome strict = search(req, false);
            if (strict.found() || !req.allowWindows()) {
                return strict;
            }
            Outcome relaxed = search(req, true);
            return relaxed.found() ? relaxed : strict;
        }

        private Outcome search(Request req, boolean allowWindows) {
            Diagnostics diag = new Diagnostics();
            TeacherLoad load = req.load();
            StudyGroup group = load.getGroup();
            Long groupId = group.getId();
            Long teacherId = req.teacherId();
            Teacher teacher = teacherId == null ? null : teachers.getOrDefault(teacherId, load.getTeacher());
            int students = group.getStudentCount() == null ? 0 : group.getStudentCount();
            String discipline = load.getDiscipline() != null ? load.getDiscipline().getName() : null;
            int weekCap = weekCap(group);
            LocalTime[] lunch = lunchFor(group);
            LocalDateTime now = LocalDateTime.now();

            LocalDate start = req.from().isBefore(from) ? from : req.from();
            LocalDate end = req.to().isAfter(to) ? to : req.to();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                if (!byDate.containsKey(d)) continue;
                if (AcademicYearUtil.semesterOfDate(d) != req.semester()) continue;
                if (d.isBefore(now.toLocalDate())) continue;
                diag.daysChecked++;
                if (isGroupBlocked(groupId, d)) { diag.blockedByEvent++; continue; }
                if (isTeacherAbsent(teacherId, d)) { diag.teacherSick++; continue; }
                if (groupWeekCount(groupId, d) >= weekCap) { diag.weekCap++; continue; }
                TreeSet<Integer> pairs = groupPairs(groupId, d);
                if (pairs.size() >= DEFAULT_GROUP_DAY_CAP) { diag.dayCap++; continue; }
                if (teacher != null && teacher.getMaxPairsPerDay() != null && teacher.getMaxPairsPerDay() > 0
                        && teacherDayCount(teacherId, d) >= teacher.getMaxPairsPerDay()) {
                    diag.teacherBusy++;
                    continue;
                }

                Slot best = null;
                double bestScore = Double.MAX_VALUE;
                for (int p = 0; p < GenerationGrid.pairsPerDay(); p++) {
                    if (d.equals(now.toLocalDate()) && !GenerationGrid.start(p).isAfter(now.toLocalTime())) continue;
                    if (pairs.contains(p)) { diag.groupBusy++; continue; }
                    if (isTeacherBusy(teacherId, d, p)) { diag.teacherBusy++; continue; }
                    if (lunch != null && GenerationGrid.overlapsLunch(p, lunch[0], lunch[1])) { diag.lunch++; continue; }
                    if (req.onlyFillGaps() && (pairs.isEmpty() || p < pairs.first() || p > pairs.last())) continue;
                    boolean window = createsWindow(pairs, p);
                    if (window && !allowWindows) { diag.wouldCreateWindow++; continue; }
                    String room = pickRoom(req.preferredRoom(), d, p, students, discipline);
                    if (room == null) { diag.noRoom++; continue; }

                    double score;
                    if (pairs.isEmpty()) score = 2;
                    else if (p > pairs.first() && p < pairs.last()) score = 0;
                    else if (!window) score = 1;
                    else score = 5 + Math.min(Math.abs(p - pairs.first()), Math.abs(p - pairs.last()));
                    if (req.preferredPair() >= 0 && p != req.preferredPair()) score += 0.5;
                    score += p * 0.01;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Slot(d, p, room, AcademicYearUtil.academicWeek(d, academicYear), window);
                    }
                }
                if (best != null) {
                    return new Outcome(best, diag);
                }
            }
            return new Outcome(null, diag);
        }

        private String pickRoom(String preferred, LocalDate d, int p, int students, String discipline) {
            if (preferred != null && !preferred.isBlank() && !isRoomBusy(preferred, d, p)) {
                return preferred;
            }
            for (GenerationGrid.Room room : rooms) {
                if (room.capacity() > 0 && students > 0 && room.capacity() < students) continue;
                if (!room.isOpenFor(discipline)) continue;
                if (!isRoomBusy(room.name(), d, p)) return room.name();
            }
            return null;
        }

        /** Занятия группы на дату (для уплотнения дня). */
        List<Occ> groupOccurrences(Long groupId, LocalDate date) {
            List<Occ> result = new ArrayList<>();
            for (Occ o : byDate.getOrDefault(date, List.of())) {
                if (groupId.equals(o.groupId)) result.add(o);
            }
            return result;
        }

        public String freeRoom(String preferred, LocalDate date, int pairIdx, int students, String discipline) {
            return pickRoom(preferred, date, pairIdx, students, discipline);
        }

        /** Все пары группы на дату (для проверки окон и выбора, какую пару снимать при излишке часов). */
        public TreeSet<Integer> pairsOfGroup(Long groupId, LocalDate date) {
            return groupPairs(groupId, date);
        }
    }
}
