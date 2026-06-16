package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.dto.DashboardDto;
import com.karyakina.schedule.dto.ProductivityDto;
import com.karyakina.schedule.dto.TeacherProfileDto;
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
                .collect(Collectors.groupingBy(l -> l.getTeacher().getId()));

        // Рассчитываем целевое значение на текущую дату
        int[] academicYears = AcademicYearUtil.getCurrentAcademicYear();
        int currentYear = LocalDate.now().getYear();
        int currentMonth = LocalDate.now().getMonthValue();

        // Определяем прогресс учебного года (0.0 - 1.0)
        // Учебный год: сентябрь - май (9 месяцев)
        int progress = calculateAcademicYearProgress();
        double targetProgress = Math.min(progress * 100, 100);

        List<ProductivityDto> result = new ArrayList<>();
        byTeacher.forEach((teacherId, teacherLoads) -> {
            Teacher t = teacherLoads.get(0).getTeacher();
            double totalPlan = teacherLoads.stream().mapToInt(TeacherLoad::getPlannedHours).sum();
            double totalRead = teacherLoads.stream().mapToInt(TeacherLoad::getReadHours).sum();

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

            // Процент выполнения плана
            double planCompletion = totalPlan > 0 ? (totalRead / totalPlan) * 100 : 0;

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
                    .formulaUsed("(План*0.5) + (Своевременность*0.3) + (Точность*0.2)")
                    .build());
        });

        result.sort(Comparator.comparingDouble(ProductivityDto::getProductivityIndex).reversed());
        return result;
    }

    private int calculateAcademicYearProgress() {
        int[] academicYears = AcademicYearUtil.getCurrentAcademicYear();
        int currentYear = LocalDate.now().getYear();
        int currentMonth = LocalDate.now().getMonthValue();

        // Учебный год: сентябрь (9) - май (5)
        // Начало: сентябрь текущего или прошлого года
        int startYear = academicYears[0];
        int startMonth = 9; // Сентябрь

        LocalDate startDate = LocalDate.of(startYear, startMonth, 1);
        LocalDate endDate = startDate.plusMonths(9); // Май
        LocalDate today = LocalDate.now();

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

        // Процент выполнения плана
        double planCompletion = totalPlan > 0 ? (totalRead / totalPlan) * 100 : 0;

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
        double targetProgress = calculateAcademicYearProgress();

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
