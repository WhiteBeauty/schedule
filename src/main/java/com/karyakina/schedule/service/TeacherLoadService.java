package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.dto.DashboardDto;
import com.karyakina.schedule.dto.ProductivityDto;
import com.karyakina.schedule.dto.TeacherProfileDto;
import com.karyakina.schedule.repository.CuratorshipRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeacherLoadService {

    private final TeacherLoadRepository loadRepository;
    private final TeacherRepository teacherRepository;
    private final CuratorshipRepository curatorshipRepository;

    public List<TeacherLoad> findByYear(Integer year) {
        return loadRepository.findByAcademicYear(year);
    }

    public List<TeacherLoad> findByTeacherAndYear(Long teacherId, Integer year) {
        return loadRepository.findByTeacherIdAndYearWithDetails(teacherId, year);
    }

    @Transactional
    public TeacherLoad updateReadHours(Long loadId, Integer newReadHours) {
        TeacherLoad load = loadRepository.findById(loadId)
                .orElseThrow(() -> new NoSuchElementException("Load not found: " + loadId));
        load.setReadHours(newReadHours);
        return loadRepository.save(load);
    }

    public List<ProductivityDto> calculateProductivity(Integer year) {
        List<TeacherLoad> loads = loadRepository.findByAcademicYear(year);
        Map<Long, List<TeacherLoad>> byTeacher = loads.stream()
                .filter(l -> l.getTeacher() != null)
                .collect(Collectors.groupingBy(l -> l.getTeacher().getId()));

        // Рассчитываем целевое значение на текущую дату
        int progress = calculateAcademicYearProgress();
        double targetProgress = Math.min(progress * 100, 100);

        List<ProductivityDto> result = new ArrayList<>();
        byTeacher.forEach((teacherId, teacherLoads) -> {
            Teacher t = teacherLoads.get(0).getTeacher();
            double totalPlan = teacherLoads.stream().mapToInt(TeacherLoad::getPlannedHours).sum();
            double totalRead = teacherLoads.stream().mapToInt(TeacherLoad::getReadHours).sum();

            // Кураторские часы (см. Curatorship): plan = запланировано на год,
            // factual = количество проведённых кураторских часов = количество записей
            // в журнале (logs) — раньше эти часы вообще нигде не учитывались.
            List<Curatorship> curatorships = curatorshipRepository.findByTeacherId(teacherId);
            double curatorshipPlan = curatorships.stream()
                    .mapToInt(c -> c.getHours() != null ? c.getHours() : 0).sum();
            double curatorshipDone = curatorships.stream()
                    .mapToInt(c -> c.getLogs() != null ? c.getLogs().size() : 0).sum();

            long totalCp = teacherLoads.stream()
                    .mapToLong(l -> l.getControlPoints().size()).sum();
            long onTimeCp = teacherLoads.stream()
                    .flatMap(l -> l.getControlPoints().stream())
                    .filter(cp -> cp.getStatus() == ControlPoint.ControlPointStatus.ON_TIME)
                    .count();

            // Точность учёта (100 - процент корректировок)
            long totalAdjustments = teacherLoads.stream()
                    .mapToLong(l -> l.getMonthlyRecords().stream()
                            .filter(r -> r.getAdjustedHours() != null && !r.getAdjustedHours().equals(r.getHours()))
                            .count())
                    .sum();
            long totalRecords = teacherLoads.stream()
                    .mapToLong(l -> l.getMonthlyRecords().size())
                    .sum();
            double accuracy = totalRecords > 0
                    ? 100.0 - ((double) totalAdjustments / totalRecords * 100)
                    : 100.0;

            double planCompletion = computePlanCompletion(
                    teacherLoads, totalPlan, totalRead, curatorshipPlan, curatorshipDone, progress);

            // Своевременность контрольных точек
            double timeliness = totalCp > 0 ? ((double) onTimeCp / totalCp) * 100 : 100;

            // Итоговая продуктивность: план 50%, своевременность 30%, точность 20%
            double index = (planCompletion * 0.5) + (timeliness * 0.3) + (accuracy * 0.2);
            index = Math.round(Math.max(0, Math.min(100, index)) * 100.0) / 100.0;

            String level;
            String color;
            if (index >= 90) {
                level = "HIGH";
                color = "success";
            } else if (index >= 70) {
                level = "MEDIUM";
                color = "warning";
            } else {
                level = "LOW";
                color = "danger";
            }

            result.add(ProductivityDto.builder()
                    .teacherId(teacherId)
                    .teacherName(t.getFullName())
                    .productivityIndex(index)
                    .color(color)
                    .level(level)
                    .planCompletionPercent(Math.round(planCompletion * 100.0) / 100.0)
                    .timelinessPercent(Math.round(timeliness * 100.0) / 100.0)
                    .accuracyPercent(Math.round(accuracy * 100.0) / 100.0)
                    .targetProgress(Math.round(targetProgress * 100.0) / 100.0)
                    .curatorshipPlannedHours(curatorshipPlan)
                    .curatorshipDoneHours(curatorshipDone)
                    .formulaUsed("(План*0.5) + (Своевременность*0.3) + (Точность*0.2); План = факт/ожидаемое-на-сегодня " +
                            "по расписанию + кураторские часы")
                    .build());
        });

        result.sort(Comparator.comparingDouble(ProductivityDto::getProductivityIndex).reversed());
        return result;
    }

    /**
     * "Выполнение плана" считается не как простое readHours/plannedHours за ВЕСЬ год
     * (это давало заниженный/оторванный от реальности процент в начале года и
     * завышенный в конце вне зависимости от того, сколько пар РЕАЛЬНО уже должно
     * было пройти), а относительно ОЖИДАЕМЫХ на сегодняшний день часов — тех, что
     * уже должны были состояться по факту еженедельного расписания
     * (см. MonthlyRecordService.recalculateHoursForLoad, который считает эти часы
     * из реальных пар в Schedule). Плюс сюда же добавляются кураторские часы.
     * Если расписание ещё не сгенерировано (expectedToDate == 0), используем долю
     * от годового плана по проценту прошедшего учебного года как разумный запасной
     * вариант, чтобы не показывать искусственный 0%.
     */
    private double computePlanCompletion(List<TeacherLoad> teacherLoads, double totalPlan, double totalRead,
                                          double curatorshipPlan, double curatorshipDone, int yearProgressPercent) {
        double expectedTeachingToDate = computeExpectedHoursToDate(teacherLoads);
        double yearProgress = yearProgressPercent / 100.0;
        double expectedCuratorshipToDate = curatorshipPlan * yearProgress;

        double expectedToDate = expectedTeachingToDate > 0.5
                ? expectedTeachingToDate + expectedCuratorshipToDate
                : (totalPlan * yearProgress) + expectedCuratorshipToDate;

        double actualToDate = totalRead + curatorshipDone;
        return expectedToDate > 0 ? Math.min(150, (actualToDate / expectedToDate) * 100) : 0;
    }

    /**
     * Сколько часов преподавания УЖЕ ДОЛЖНО было состояться к сегодняшнему дню по
     * факту реального расписания (не по среднегодовой доле, а по конкретным дням
     * недели пар в MonthlyRecord.hours, которые в свою очередь посчитаны из Schedule).
     * Полностью прошедшие месяцы считаются целиком, текущий месяц — пропорционально
     * прошедшим дням.
     */
    private double computeExpectedHoursToDate(List<TeacherLoad> teacherLoads) {
        LocalDate today = LocalDate.now();
        double total = 0;
        for (TeacherLoad load : teacherLoads) {
            if (load.getAcademicYear() == null) continue;
            int academicYearStart = load.getAcademicYear();
            for (MonthlyRecord mr : load.getMonthlyRecords()) {
                int hours = mr.getHours() != null ? mr.getHours() : 0;
                if (hours == 0) continue;
                int calendarYear = mr.getMonth() >= 9 ? academicYearStart : academicYearStart + 1;
                LocalDate monthStart = LocalDate.of(calendarYear, mr.getMonth(), 1);
                LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
                if (today.isAfter(monthEnd)) {
                    total += hours;
                } else if (!today.isBefore(monthStart)) {
                    double fraction = today.getDayOfMonth() / (double) monthStart.lengthOfMonth();
                    total += hours * fraction;
                }
                // будущие месяцы (ещё не наступили) в ожидаемое "на сегодня" не входят
            }
        }
        return total;
    }

    private int calculateAcademicYearProgress() {
        LocalDate today = LocalDate.now();
        int currentYear = today.getYear();
        int currentMonth = today.getMonthValue();

        // Реальные границы текущего учебного года (сентябрь — май) считаются здесь
        // отдельно от AcademicYearUtil: там "текущий год" — это год, который показывают
        // в интерфейсе (совпадает с календарным), а прогресс должен отражать фактическое
        // положение в учебном цикле сентябрь-май независимо от того, какой год выбран
        // для фильтрации данных.
        int startYear = currentMonth >= 9 ? currentYear : currentYear - 1;
        int startMonth = 9; // Сентябрь

        LocalDate startDate = LocalDate.of(startYear, startMonth, 1);
        LocalDate endDate = startDate.plusMonths(9); // Май

        if (today.isBefore(startDate)) {
            return 0;
        }
        if (today.isAfter(endDate)) {
            return 100;
        }

        long totalDays = startDate.until(endDate).getDays();
        long elapsedDays = startDate.until(today).getDays();

        return (int) ((double) elapsedDays / totalDays * 100);
    }

    public TeacherProfileDto buildProfile(Long teacherId, Integer year) {
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));
        List<TeacherLoad> loads = loadRepository.findByTeacherIdAndAcademicYear(teacherId, year);

        int totalPlanned = loads.stream().mapToInt(TeacherLoad::getPlannedHours).sum();
        int totalRead = loads.stream().mapToInt(TeacherLoad::getReadHours).sum();

        List<TeacherProfileDto.LoadSummaryDto> summaries = loads.stream().map(l ->
                TeacherProfileDto.LoadSummaryDto.builder()
                        .loadId(l.getId())
                        .discipline(l.getDiscipline().getName())
                        .groupName(l.getGroup().getName())
                        .planned(l.getPlannedHours())
                        .read(l.getReadHours())
                        .remaining(l.getRemainingHours())
                        .completion(Math.round(l.getCompletionPercent() * 100.0) / 100.0)
                        .build()
        ).toList();

        // Динамика по месяцам (сентябрь - август)
        Map<String, Integer> monthMap = new LinkedHashMap<>();
        List<Month> academicMonths = List.of(
                Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER, Month.DECEMBER,
                Month.JANUARY, Month.FEBRUARY, Month.MARCH, Month.APRIL,
                Month.MAY, Month.JUNE, Month.JULY, Month.AUGUST
        );
        academicMonths.forEach(m -> monthMap.put(m.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru")), 0));

        loads.stream()
                .flatMap(l -> l.getMonthlyRecords().stream())
                .forEach(mr -> {
                    String name = Month.of(mr.getMonth()).getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru"));
                    monthMap.merge(name, mr.getEffectiveHours(), Integer::sum);
                });

        List<TeacherProfileDto.MonthlyHoursDto> dynamics = monthMap.entrySet().stream()
                .map(e -> TeacherProfileDto.MonthlyHoursDto.builder().month(e.getKey()).hours(e.getValue()).build())
                .toList();

        double avgProd = calculateProductivity(year).stream()
                .filter(p -> p.getTeacherId().equals(teacherId))
                .findFirst()
                .map(ProductivityDto::getProductivityIndex)
                .orElse(0.0);

        String initials = buildInitials(teacher.getFullName());
        String level = avgProd >= 90 ? "HIGH" : (avgProd >= 70 ? "MEDIUM" : "LOW");

        return TeacherProfileDto.builder()
                .id(teacher.getId())
                .fullName(teacher.getFullName())
                .initials(initials)
                .department(teacher.getDepartment())
                .position(teacher.getPosition())
                .email(teacher.getEmail())
                .phone(teacher.getPhone())
                .rate(teacher.getRate())
                .birthDate(teacher.getBirthDate())
                .totalDisciplines(loads.size())
                .totalPlannedHours(totalPlanned)
                .totalReadHours(totalRead)
                .totalRemainingHours(totalPlanned - totalRead)
                .avgProductivity(avgProd)
                .productivityIndex(avgProd)
                .productivityLevel(level)
                .loads(summaries)
                .monthlyDynamics(dynamics)
                .build();
    }

    public DashboardDto buildDashboard(Long teacherId, Integer year) {
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));
        List<TeacherLoad> loads = loadRepository.findByTeacherIdAndAcademicYear(teacherId, year);

        // Продуктивность по новой формуле
        double totalPlan = loads.stream().mapToInt(TeacherLoad::getPlannedHours).sum();
        double totalRead = loads.stream().mapToInt(TeacherLoad::getReadHours).sum();

        long totalCp = loads.stream().mapToLong(l -> l.getControlPoints().size()).sum();
        long onTimeCp = loads.stream()
                .flatMap(l -> l.getControlPoints().stream())
                .filter(cp -> cp.getStatus() == ControlPoint.ControlPointStatus.ON_TIME)
                .count();

        // Точность учёта
        long totalAdjustments = loads.stream()
                .mapToLong(l -> l.getMonthlyRecords().stream()
                        .filter(r -> r.getAdjustedHours() != null && !r.getAdjustedHours().equals(r.getHours()))
                        .count())
                .sum();
        long totalRecords = loads.stream()
                .mapToLong(l -> l.getMonthlyRecords().size())
                .sum();
        double accuracy = totalRecords > 0
                ? 100.0 - ((double) totalAdjustments / totalRecords * 100)
                : 100.0;

        int academicYearProgress = calculateAcademicYearProgress();
        List<Curatorship> curatorships = curatorshipRepository.findByTeacherId(teacherId);
        double curatorshipPlan = curatorships.stream()
                .mapToInt(c -> c.getHours() != null ? c.getHours() : 0).sum();
        double curatorshipDone = curatorships.stream()
                .mapToInt(c -> c.getLogs() != null ? c.getLogs().size() : 0).sum();

        // Процент выполнения плана — по факту от расписания на сегодняшний день + кураторские часы
        double planCompletion = computePlanCompletion(
                loads, totalPlan, totalRead, curatorshipPlan, curatorshipDone, academicYearProgress);

        // Своевременность контрольных точек
        double timeliness = totalCp > 0 ? ((double) onTimeCp / totalCp) * 100 : 100;

        // Итоговая продуктивность: план 50%, своевременность 30%, точность 20%
        double index = (planCompletion * 0.5) + (timeliness * 0.3) + (accuracy * 0.2);
        index = Math.round(Math.max(0, Math.min(100, index)) * 100.0) / 100.0;

        String level;
        String color;
        if (index >= 90) {
            level = "HIGH";
            color = "success";
        } else if (index >= 70) {
            level = "MEDIUM";
            color = "warning";
        } else {
            level = "LOW";
            color = "danger";
        }

        // Рассчитываем целевое значение на текущую дату
        double targetProgress = academicYearProgress;

        DashboardDto.ProductivityBarDto productivity = DashboardDto.ProductivityBarDto.builder()
                .index(index)
                .target(Math.round(targetProgress * 100.0) / 100.0)
                .level(level)
                .color(color)
                .planCompletionPercent(Math.round(planCompletion * 100.0) / 100.0)
                .timelinessPercent(Math.round(timeliness * 100.0) / 100.0)
                .accuracyPercent(Math.round(accuracy * 100.0) / 100.0)
                .build();

        // Нагрузка — строки таблицы
        List<DashboardDto.TeacherLoadRowDto> rows = loads.stream().map(l ->
                DashboardDto.TeacherLoadRowDto.builder()
                        .id(l.getId())
                        .discipline(l.getDiscipline().getName())
                        .controlPoint1(l.getControlPoint1())
                        .controlPoint2(l.getControlPoint2())
                        .plannedHoursFirst(l.getFirstSemesterHours() != null ? l.getFirstSemesterHours() : 0)
                        .plannedHoursSecond(l.getSecondSemesterHours() != null ? l.getSecondSemesterHours() : 0)
                        .totalPlannedHours(l.getPlannedHours())
                        .remainingHours(l.getRemainingHours())
                        .readHours(l.getReadHours())
                        .groupName(l.getGroup().getName())
                        .build()
        ).toList();

        int totalDisciplines = loads.size();
        int totalGroups = (int) loads.stream().map(l -> l.getGroup().getId()).distinct().count();
        int totalHours1 = rows.stream().mapToInt(DashboardDto.TeacherLoadRowDto::getPlannedHoursFirst).sum();
        int totalHours2 = rows.stream().mapToInt(DashboardDto.TeacherLoadRowDto::getPlannedHoursSecond).sum();
        int totalYearHours = rows.stream().mapToInt(DashboardDto.TeacherLoadRowDto::getTotalPlannedHours).sum();
        int totalRemaining = rows.stream().mapToInt(DashboardDto.TeacherLoadRowDto::getRemainingHours).sum();
        int totalReadSum = rows.stream().mapToInt(DashboardDto.TeacherLoadRowDto::getReadHours).sum();

        TeacherProfileDto profile = buildProfile(teacherId, year);

        String initials = buildInitials(teacher.getFullName());

        return DashboardDto.builder()
                .id(teacher.getId())
                .fullName(teacher.getFullName())
                .initials(initials)
                .department(teacher.getDepartment())
                .position(teacher.getPosition())
                .email(teacher.getEmail())
                .phone(teacher.getPhone())
                .rate(teacher.getRate())
                .birthDate(teacher.getBirthDate())
                .productivity(productivity)
                .loads(rows)
                .totalDisciplines(totalDisciplines)
                .totalGroups(totalGroups)
                .totalHours1(totalHours1)
                .totalHours2(totalHours2)
                .totalYearHours(totalYearHours)
                .totalRemaining(totalRemaining)
                .totalRead(totalReadSum)
                .profile(profile)
                .build();
    }

    private String buildInitials(String fullName) {
        if (fullName == null || fullName.isBlank()) return "?";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, 1).toUpperCase();
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
    }
}
