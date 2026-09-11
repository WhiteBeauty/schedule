package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Classroom;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.ClassroomRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.service.generator.GenerationGrid;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * "Проверка вставленных пар за месяц" — новая вкладка сверки часов. Смотрит на все
 * нагрузки (кроме практики/вождения — их размещает администратор отдельно, см.
 * {@code ScheduleGeneratorService#isPracticeOrDriving}), сравнивает, сколько пар в
 * неделю ДОЛЖНО быть по тарификации (те же формулы, что при автосоставлении) с тем,
 * сколько реально СТОИТ в {@link Schedule} — и для нехватки сразу предлагает свободные
 * слоты (день + пара + аудитория) для ТОГО ЖЕ преподавателя/группы этой нагрузки, где
 * нет накладки. Администратор жмёт «Поставить» — пара создаётся обычным
 * ScheduleService.createSchedule (та же самая проверка накладок, что и везде).
 */
@Service
@RequiredArgsConstructor
public class PlacementAuditService {

    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;
    private final ClassroomRepository classroomRepository;
    private final LessonInstanceService lessonInstanceService;

    @Data
    @Builder
    public static class SlotSuggestion {
        private String dayOfWeek;
        private int pairIndex;
        private String startTime;
        private String endTime;
        private String room;
    }

    @Data
    @Builder
    public static class PlacementGap {
        private Long loadId;
        private String teacherName;
        private String disciplineName;
        private String groupName;
        private int expectedPairsPerWeek;
        private int actualPairsPerWeek;
        private int missingPairs;
        private List<SlotSuggestion> suggestedSlots;
    }

    /** Регексы те же, что isPracticeOrDriving в ScheduleGeneratorService — держать в синхроне при правках там. */
    private boolean isPracticeOrDriving(String disciplineName) {
        if (disciplineName == null) return false;
        String normalized = disciplineName.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (normalized.contains("вождение")) return true;
        return normalized.matches("^(пп|уп)[\\s.].*") || normalized.matches("^(пп|уп)\\d.*");
    }

    public List<PlacementGap> auditMonth(Integer academicYear, int month) {
        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        int calendarYear = month >= 9 ? year : year + 1;
        LocalDate representative = LocalDate.of(calendarYear, month, 15);
        int semester = AcademicYearUtil.semesterOfDate(representative);
        if (semester == 0) {
            return List.of(); // весь месяц — каникулы, проверять нечего
        }
        int weeksInSemester = AcademicYearUtil.getSemesterWeeks(semester, year);

        List<TeacherLoad> loads = loadRepository.findByAcademicYear(year);
        List<Schedule> allSchedules = scheduleRepository.findByAcademicYear(year);
        Map<Long, List<Schedule>> schedulesByLoad = new HashMap<>();
        for (Schedule s : allSchedules) {
            if (s.getTeacherLoad() == null) continue;
            schedulesByLoad.computeIfAbsent(s.getTeacherLoad().getId(), k -> new ArrayList<>()).add(s);
        }
        List<GenerationGrid.Room> rooms = GenerationGrid.rooms(classroomRepository.findAll());

        List<PlacementGap> gaps = new ArrayList<>();
        for (TeacherLoad load : loads) {
            if (load.getDiscipline() == null || load.getGroup() == null) continue;
            if (isPracticeOrDriving(load.getDiscipline().getName())) continue;

            int semHours = semester == 2
                    ? (load.getSecondSemesterHours() == null ? 0 : load.getSecondSemesterHours())
                    : (load.getFirstSemesterHours() == null ? 0 : load.getFirstSemesterHours());
            if (semHours <= 0) continue; // в этом семестре предмета нет вообще — не нехватка

            int expectedPairs = Math.max(0, (int) Math.round(semHours / (double) weeksInSemester / 2.0));
            if (expectedPairs <= 0) continue;

            List<Schedule> own = schedulesByLoad.getOrDefault(load.getId(), List.of());
            // Считаем УНИКАЛЬНЫЕ (день, пара) — так же, как реально повторяется в расписании
            // каждую неделю (academicWeek==null); записи "только на одну неделю" (числитель/
            // знаменатель или перенос из-за экзамена/практики) в этот подсчёт не включаем —
            // это не постоянное место в сетке.
            long actualPairs = own.stream()
                    .filter(s -> s.getAcademicWeek() == null)
                    .filter(s -> s.getSemester() == null || s.getSemester().equals(semester))
                    .map(s -> s.getDayOfWeek() + "|" + s.getStartTime())
                    .distinct()
                    .count();

            int missing = expectedPairs - (int) actualPairs;
            if (missing <= 0) continue;

            List<SlotSuggestion> slots = findFreeSlots(load, allSchedules, rooms, semester, Math.min(missing, 3));
            gaps.add(PlacementGap.builder()
                    .loadId(load.getId())
                    .teacherName(load.getTeacher() != null ? load.getTeacher().getFullName() : "Не назначен")
                    .disciplineName(load.getDiscipline().getName())
                    .groupName(load.getGroup().getName())
                    .expectedPairsPerWeek(expectedPairs)
                    .actualPairsPerWeek((int) actualPairs)
                    .missingPairs(missing)
                    .suggestedSlots(slots)
                    .build());
        }
        gaps.sort(Comparator.comparing(PlacementGap::getTeacherName).thenComparing(PlacementGap::getDisciplineName));
        return gaps;
    }

    /** Свободные (день, пара, аудитория) для преподавателя+группы этой нагрузки — без накладок. */
    private List<SlotSuggestion> findFreeSlots(TeacherLoad load, List<Schedule> allSchedules,
                                               List<GenerationGrid.Room> rooms, int semester, int limit) {
        Long teacherId = load.getTeacher() != null ? load.getTeacher().getId() : null;
        Long groupId = load.getGroup() != null ? load.getGroup().getId() : null;
        int studentCount = load.getGroup().getStudentCount() == null ? 0 : load.getGroup().getStudentCount();
        String disciplineName = load.getDiscipline().getName();

        List<SlotSuggestion> result = new ArrayList<>();
        for (DayOfWeek day : GenerationGrid.WORK_DAYS) {
            for (int pairIdx = 0; pairIdx < GenerationGrid.pairsPerDay(); pairIdx++) {
                if (result.size() >= limit) return result;
                java.time.LocalTime start = GenerationGrid.start(pairIdx);

                boolean teacherBusy = teacherId != null && allSchedules.stream().anyMatch(s ->
                        s.getTeacherLoad() != null && s.getTeacherLoad().getTeacher() != null
                                && teacherId.equals(s.getTeacherLoad().getTeacher().getId())
                                && s.getDayOfWeek() == day && s.getStartTime().equals(start)
                                && (s.getSemester() == null || s.getSemester() == semester));
                if (teacherBusy) continue;

                boolean groupBusy = groupId != null && allSchedules.stream().anyMatch(s ->
                        s.getTeacherLoad() != null && s.getTeacherLoad().getGroup() != null
                                && groupId.equals(s.getTeacherLoad().getGroup().getId())
                                && s.getDayOfWeek() == day && s.getStartTime().equals(start)
                                && (s.getSemester() == null || s.getSemester() == semester));
                if (groupBusy) continue;

                for (GenerationGrid.Room room : rooms) {
                    if (room.capacity() > 0 && studentCount > room.capacity()) continue;
                    if (!room.isOpenFor(disciplineName)) continue;
                    boolean roomBusy = allSchedules.stream().anyMatch(s ->
                            room.name().equals(s.getClassroom()) && s.getDayOfWeek() == day
                                    && s.getStartTime().equals(start)
                                    && (s.getSemester() == null || s.getSemester() == semester));
                    if (roomBusy) continue;

                    result.add(SlotSuggestion.builder()
                            .dayOfWeek(GenerationGrid.dayName(GenerationGrid.dayIndex(day)))
                            .pairIndex(pairIdx)
                            .startTime(start.toString())
                            .endTime(GenerationGrid.end(pairIdx).toString())
                            .room(room.name())
                            .build());
                    break;
                }
            }
        }
        return result;
    }
}
