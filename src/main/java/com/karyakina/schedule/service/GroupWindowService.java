package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.Notification;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroupWindowService {

    private final CalendarSlotFinder slotFinder;
    private final ScheduleRepository scheduleRepository;
    private final LessonInstanceRepository lessonInstanceRepository;
    private final LessonInstanceService lessonInstanceService;
    private final NotificationService notificationService;

    public record Outcome(int shiftedInDay, List<String> warnings) {
    }

    @Transactional
    public Outcome closeWindows(Map<Long, ? extends Set<LocalDate>> holes, int academicYear, Long sickLeaveId) {
        if (holes == null || holes.isEmpty()) return new Outcome(0, List.of());
        LocalDate today = LocalDate.now();
        LocalDate min = null;
        for (Set<LocalDate> dates : holes.values()) {
            for (LocalDate d : dates) {
                if (min == null || d.isBefore(min)) min = d;
            }
        }
        if (min == null) return new Outcome(0, List.of());
        CalendarSlotFinder.Calendar cal = slotFinder.open(academicYear, min.isBefore(today) ? today : min,
                AcademicYearUtil.semesterEnd(2, academicYear));
        int shifted = 0;
        List<String> warnings = new java.util.ArrayList<>();
        for (Map.Entry<Long, ? extends Set<LocalDate>> entry : holes.entrySet()) {
            Long groupId = entry.getKey();
            for (LocalDate date : entry.getValue()) {
                if (date.isBefore(today)) continue;
                for (int guard = 0; guard < GenerationGrid.pairsPerDay() * 2; guard++) {
                    TreeSet<Integer> pairs = cal.pairsOfGroup(groupId, date);
                    int gap = firstGap(pairs);
                    if (gap < 0) break;
                    if (shiftIntoGap(cal, groupId, date, gap, pairs, academicYear, sickLeaveId)) {
                        shifted++;
                        continue;
                    }
                    String groupName = cal.groupOccurrences(groupId, date).stream().findFirst()
                            .flatMap(o -> scheduleRepository.findById(o.scheduleId))
                            .map(s -> s.getTeacherLoad().getGroup().getName()).orElse("группа #" + groupId);
                    warnings.add("Внимание: у группы " + groupName + " " + date + " окно на " + (gap + 1)
                            + "-й паре — сдвинуть в него другую пару этого дня не получилось.");
                    break;
                }
            }
        }
        return new Outcome(shifted, warnings);
    }

    static int firstGap(TreeSet<Integer> pairs) {
        if (pairs.size() < 2) return -1;
        for (int p = pairs.first() + 1; p < pairs.last(); p++) {
            if (!pairs.contains(p)) return p;
        }
        return -1;
    }

    private boolean shiftIntoGap(CalendarSlotFinder.Calendar cal, Long groupId, LocalDate date, int gap,
                                 TreeSet<Integer> pairs, int academicYear, Long sickLeaveId) {
        Set<Integer> candidates = new LinkedHashSet<>(List.of(pairs.last(), pairs.ceiling(gap), pairs.floor(gap), pairs.first()));
        for (int idx : candidates) {
            CalendarSlotFinder.Occ occ = occurrenceAt(cal, groupId, date, idx);
            if (occ == null) continue;
            Schedule source = scheduleRepository.findById(occ.scheduleId).orElse(null);
            if (source == null || source.getTeacherLoad() == null) continue;
            if (cal.isTeacherBusy(occ.teacherId, date, gap) || cal.isTeacherAbsent(occ.teacherId, date)) continue;
            TeacherLoad load = source.getTeacherLoad();
            String room = cal.freeRoom(source.getClassroom(), date, gap, students(load), discipline(load));
            if (room == null) continue;
            LessonInstance li = plannedInstance(source, date, academicYear);
            if (li == null) continue;

            int week = AcademicYearUtil.academicWeek(date, academicYear);
            cancel(li, "Сдвинута на " + (gap + 1) + "-ю пару, чтобы у группы не было окна", sickLeaveId);
            Schedule row = saveOneOff(load, date, gap, room, week, academicYear, date,
                    "Сдвиг внутри дня: закрытие окна у группы", sickLeaveId);
            cal.release(source.getId(), date);
            cal.occupy(new CalendarSlotFinder.Slot(date, gap, room, week, false), load, occ.teacherId, row.getId());
            lessonInstanceService.generateInstancesForDate(date, academicYear);
            notify(load, date + ": " + discipline(load) + ", группа " + load.getGroup().getName() + " — вместо "
                    + (idx + 1) + "-й пары будет " + (gap + 1) + "-я (" + GenerationGrid.start(gap) + "), ауд. " + room + ".");
            return true;
        }
        return false;
    }

    private CalendarSlotFinder.Occ occurrenceAt(CalendarSlotFinder.Calendar cal, Long groupId, LocalDate date, int idx) {
        return cal.groupOccurrences(groupId, date).stream()
                .filter(o -> o.pairIdx == idx && o.scheduleId != null).findFirst().orElse(null);
    }

    private LessonInstance plannedInstance(Schedule source, LocalDate date, int academicYear) {
        lessonInstanceService.generateInstancesForDate(date, academicYear);
        LessonInstance li = lessonInstanceRepository.findByScheduleIdAndLessonDate(source.getId(), date).orElse(null);
        return li != null && li.getStatus() == LessonInstance.Status.PLANNED ? li : null;
    }

    private void cancel(LessonInstance li, String note, Long sickLeaveId) {
        LessonInstance cancelled = lessonInstanceService.cancelInstance(li.getId(), note, "system:no-group-windows");
        if (sickLeaveId != null) {
            cancelled.setCancelledBySickLeaveId(sickLeaveId);
            lessonInstanceRepository.save(cancelled);
        }
    }

    private Schedule saveOneOff(TeacherLoad load, LocalDate date, int pairIdx, String room, int week, int academicYear,
                                LocalDate fromDate, String reason, Long sickLeaveId) {
        return scheduleRepository.save(Schedule.builder()
                .teacherLoad(load)
                .dayOfWeek(date.getDayOfWeek())
                .startTime(GenerationGrid.start(pairIdx))
                .endTime(GenerationGrid.end(pairIdx))
                .classroom(room)
                .academicWeek(week)
                .academicYear(academicYear)
                .semester(AcademicYearUtil.semesterOfDate(date))
                .rescheduledFromDate(fromDate)
                .rescheduledReason(reason)
                .sickLeaveId(sickLeaveId)
                .build());
    }

    private void notify(TeacherLoad load, String message) {
        try {
            if (load.getTeacher() != null) {
                notificationService.notifyTeacher(load.getTeacher(), Notification.Type.SCHEDULE_CHANGED,
                        "Изменение пары", message, null);
            }
            notificationService.notifyAdmins(Notification.Type.SCHEDULE_CHANGED, "Пара сдвинута (без окна у группы)",
                    message, null);
        } catch (Exception e) {
            log.warn("Не удалось отправить уведомление: {}", e.getMessage());
        }
    }

    private static int students(TeacherLoad load) {
        return load.getGroup() == null || load.getGroup().getStudentCount() == null ? 0 : load.getGroup().getStudentCount();
    }

    private static String discipline(TeacherLoad load) {
        return load.getDiscipline() != null ? load.getDiscipline().getName() : "Пара";
    }
}
