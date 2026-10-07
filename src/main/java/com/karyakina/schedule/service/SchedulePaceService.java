package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.MonthlyRecord;
import com.karyakina.schedule.domain.Notification;
import com.karyakina.schedule.domain.PaceAdjustmentSuggestion;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.MonthlyRecordRepository;
import com.karyakina.schedule.repository.NotificationRepository;
import com.karyakina.schedule.repository.PaceAdjustmentSuggestionRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.service.generator.SolverConfig;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulePaceService {

    private final ScheduleRepository scheduleRepository;
    private final MonthlyRecordRepository monthlyRecordRepository;
    private final PaceAdjustmentSuggestionRepository suggestionRepository;
    private final NotificationRepository notificationRepository;
    private final SettingsService settingsService;
    private final LessonInstanceService lessonInstanceService;

    private static final int MIN_PAIR_DEVIATION = 1;

    @Scheduled(cron = "0 0 6 * * MON")
    public void scheduledPaceCheck() {
        if (!settingsService.isPaceRecalculationEnabled()) {
            return;
        }
        log.info("Running scheduled schedule pace check...");
        try {
            int academicYear = AcademicYearUtil.getCurrentAcademicYearStart();
            int semester = AcademicYearUtil.getCurrentSemester();
            List<PaceAdjustmentSuggestion> created = checkPace(academicYear, semester);
            log.info("Pace check completed: {} suggestion(s) created", created.size());
        } catch (Exception e) {
            log.error("Error during scheduled pace check: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public List<PaceAdjustmentSuggestion> checkPace(int academicYear, int semester) {
        List<PaceAdjustmentSuggestion> created = new ArrayList<>();
        LocalDate today = LocalDate.now();
        int currentWeek = lessonInstanceService.computeAcademicWeek(today, academicYear);
        LocalDate semesterEnd = AcademicYearUtil.semesterEnd(semester, academicYear);
        int semesterEndWeek = lessonInstanceService.computeAcademicWeek(semesterEnd, academicYear);
        int remainingWeeks = Math.max(1, semesterEndWeek - currentWeek + 1);

        SolverConfig config = SolverConfig.defaults();

        List<Schedule> relevant = scheduleRepository.findByAcademicYear(academicYear).stream()
                .filter(s -> s.getTeacherLoad() != null)
                .filter(s -> s.getSemester() == null || s.getSemester().equals(semester))
                .toList();
        Map<Long, List<Schedule>> byLoad = relevant.stream()
                .collect(Collectors.groupingBy(s -> s.getTeacherLoad().getId()));

        for (Map.Entry<Long, List<Schedule>> entry : byLoad.entrySet()) {
            Long loadId = entry.getKey();
            List<Schedule> loadSchedules = entry.getValue();
            TeacherLoad load = loadSchedules.get(0).getTeacherLoad();

            int plannedSemesterHours = semester == 2
                    ? nz(load.getSecondSemesterHours())
                    : nz(load.getFirstSemesterHours());
            if (plannedSemesterHours <= 0) continue;

            long currentWeeklyPairs = loadSchedules.stream()
                    .filter(s -> s.getAcademicWeek() == null)
                    .filter(s -> s.getActiveFromWeek() == null || s.getActiveFromWeek() <= currentWeek)
                    .filter(s -> s.getActiveUntilWeek() == null || s.getActiveUntilWeek() >= currentWeek)
                    .count();
            if (currentWeeklyPairs == 0) continue;

            if (suggestionRepository.existsByTeacherLoadIdAndAcademicYearAndSemesterAndStatus(
                    loadId, academicYear, semester, PaceAdjustmentSuggestion.Status.PENDING)) {
                continue;
            }

            double actualHoursToDate = monthlyRecordRepository.findByTeacherLoadId(loadId).stream()
                    .filter(r -> isMonthInSemester(r.getMonth(), semester))
                    .mapToInt(MonthlyRecord::getConductedHoursOrZero)
                    .sum();
            double expectedHoursToDate = expectedHoursToDate(plannedSemesterHours, semester, academicYear, currentWeek);

            double remainingHours = Math.max(0, plannedSemesterHours - actualHoursToDate);
            int suggestedWeeklyPairs = (int) Math.round(
                    remainingHours / remainingWeeks / config.academicHoursPerPair());

            if (Math.abs(suggestedWeeklyPairs - currentWeeklyPairs) < MIN_PAIR_DEVIATION) continue;

            int effectiveFromWeek = currentWeek + 1;
            boolean isIncrease = suggestedWeeklyPairs > currentWeeklyPairs;

            String reason = String.format(Locale.ROOT,
                    "%s у группы %s (%s): проведено %.0f из %d ч плана на семестр, ожидалось к этой неделе "
                            + "~%.0f ч. При текущем темпе (%d пар/нед) на оставшиеся %d нед. останется "
                            + "%s часов, чем нужно по плану. Рекомендуемый темп: %d пар/нед, с %d-й недели.%s",
                    load.getDiscipline().getName(), load.getGroup().getName(), load.getTeacher() != null
                            ? load.getTeacher().getFullName() : "преподаватель не назначен",
                    actualHoursToDate, plannedSemesterHours, expectedHoursToDate,
                    currentWeeklyPairs, remainingWeeks,
                    isIncrease ? "меньше" : "больше",
                    suggestedWeeklyPairs, effectiveFromWeek,
                    isIncrease ? " Увеличение пар система не расставляет автоматически — "
                            + "после подтверждения добавьте их вручную в «Расписании пар»." : "");

            PaceAdjustmentSuggestion suggestion = suggestionRepository.save(PaceAdjustmentSuggestion.builder()
                    .teacherLoad(load)
                    .academicYear(academicYear)
                    .semester(semester)
                    .currentWeeklyPairs((int) currentWeeklyPairs)
                    .suggestedWeeklyPairs(Math.max(0, suggestedWeeklyPairs))
                    .effectiveFromWeek(effectiveFromWeek)
                    .expectedHoursToDate(expectedHoursToDate)
                    .actualHoursToDate(actualHoursToDate)
                    .reason(reason)
                    .build());

            Notification notification = Notification.builder()
                    .forAdmins(true)
                    .type(Notification.Type.PACE_ADJUSTMENT_SUGGESTED)
                    .title("Пересчёт темпа: " + load.getDiscipline().getName() + " / " + load.getGroup().getName())
                    .message(reason)
                    .paceAdjustmentId(suggestion.getId())
                    .linkUrl("/notifications")
                    .build();
            notificationRepository.save(notification);

            created.add(suggestion);
        }
        return created;
    }

    @Transactional
    public PaceAdjustmentSuggestion accept(Long suggestionId) {
        PaceAdjustmentSuggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new RuntimeException("Предложение не найдено: " + suggestionId));
        if (suggestion.getStatus() != PaceAdjustmentSuggestion.Status.PENDING) {
            return suggestion;
        }

        int delta = suggestion.getSuggestedWeeklyPairs() - suggestion.getCurrentWeeklyPairs();
        if (delta < 0) {
            int lastActiveWeek = suggestion.getEffectiveFromWeek() - 1;
            List<Schedule> candidates = scheduleRepository.findByTeacherLoadId(suggestion.getTeacherLoad().getId())
                    .stream()
                    .filter(s -> s.getAcademicWeek() == null)
                    .filter(s -> s.getSemester() == null || s.getSemester().equals(suggestion.getSemester()))
                    .filter(s -> s.getActiveUntilWeek() == null || s.getActiveUntilWeek() >= suggestion.getEffectiveFromWeek())
                    .limit(-delta)
                    .toList();
            candidates.forEach(s -> s.setActiveUntilWeek(lastActiveWeek));
            scheduleRepository.saveAll(candidates);
        }

        suggestion.setStatus(PaceAdjustmentSuggestion.Status.ACCEPTED);
        suggestion.setResolvedAt(LocalDateTime.now());
        return suggestionRepository.save(suggestion);
    }

    @Transactional
    public PaceAdjustmentSuggestion decline(Long suggestionId) {
        PaceAdjustmentSuggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new RuntimeException("Предложение не найдено: " + suggestionId));
        if (suggestion.getStatus() != PaceAdjustmentSuggestion.Status.PENDING) {
            return suggestion;
        }
        suggestion.setStatus(PaceAdjustmentSuggestion.Status.DECLINED);
        suggestion.setResolvedAt(LocalDateTime.now());
        return suggestionRepository.save(suggestion);
    }

    private double expectedHoursToDate(int plannedSemesterHours, int semester, int academicYear, int currentWeek) {
        LocalDate semesterStart = AcademicYearUtil.semesterStart(semester, academicYear);
        int semesterStartWeek = lessonInstanceService.computeAcademicWeek(semesterStart, academicYear);
        int totalWeeks = AcademicYearUtil.getSemesterWeeks(semester, academicYear);
        int weeksElapsed = Math.max(0, Math.min(totalWeeks, currentWeek - semesterStartWeek + 1));
        return plannedSemesterHours * ((double) weeksElapsed / totalWeeks);
    }

    private boolean isMonthInSemester(int month, int semester) {
        return semester == 1 ? (month >= 9 && month <= 12) : (month >= 1 && month <= 6);
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
