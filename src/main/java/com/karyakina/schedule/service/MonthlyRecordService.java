package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.MonthlyRecord;
import com.karyakina.schedule.domain.Schedule;
import com.karyakina.schedule.domain.TeacherLoad;
import com.karyakina.schedule.repository.LessonInstanceRepository;
import com.karyakina.schedule.repository.MonthlyRecordRepository;
import com.karyakina.schedule.repository.ScheduleRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.util.AcademicYearUtil;
import com.karyakina.schedule.util.ScheduleOccurrence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class MonthlyRecordService {

    private final MonthlyRecordRepository repository;
    private final TeacherLoadRepository loadRepository;
    private final ScheduleRepository scheduleRepository;
    private final LessonInstanceRepository instanceRepository;

    public List<MonthlyRecord> findByLoad(Long loadId) {
        return repository.findByTeacherLoadId(loadId);
    }

    public List<MonthlyRecord> findAggregatedByLoad(Long loadId) {
        Map<String, MonthlyRecord> byMonth = new HashMap<>();
        for (MonthlyRecord rec : repository.findByTeacherLoadId(loadId)) {
            String key = rec.getYear() + "-" + rec.getMonth();
            MonthlyRecord agg = byMonth.get(key);
            if (agg == null) {
                byMonth.put(key, MonthlyRecord.builder()
                        .id(rec.getId())
                        .teacherLoad(rec.getTeacherLoad())
                        .month(rec.getMonth())
                        .year(rec.getYear())
                        .hours(nz(rec.getHours()))
                        .conductedHours(rec.getConductedHoursOrZero())
                        .adjustedHours(rec.getAdjustedHours())
                        .note(rec.getNote())
                        .changedBy(rec.getChangedBy())
                        .changedAt(rec.getChangedAt())
                        .build());
                continue;
            }
            agg.setHours(nz(agg.getHours()) + nz(rec.getHours()));
            agg.setConductedHours(agg.getConductedHoursOrZero() + rec.getConductedHoursOrZero());
            if (rec.getAdjustedHours() != null) agg.setAdjustedHours(rec.getAdjustedHours());
            if (rec.getNote() != null && !rec.getNote().isBlank()) agg.setNote(rec.getNote());
            if (rec.getChangedBy() != null) agg.setChangedBy(rec.getChangedBy());
        }
        List<MonthlyRecord> result = new ArrayList<>(byMonth.values());
        result.sort((a, b) -> {
            int y = Integer.compare(a.getYear(), b.getYear());
            return y != 0 ? y : Integer.compare(academicOrder(a.getMonth()), academicOrder(b.getMonth()));
        });
        return result;
    }

    // правка месяца меняет и общее вычитано
    @Transactional
    public MonthlyRecord adjust(Long recordId, Integer adjustedHours, String note, String changedBy) {
        MonthlyRecord rec = repository.findById(recordId)
                .orElseThrow(() -> new RuntimeException("Record not found"));
        if (adjustedHours != null && adjustedHours >= 0) {
            int delta = adjustedHours - rec.getConductedHoursOrZero();
            TeacherLoad load = rec.getTeacherLoad();
            load.setReadHours(Math.max(0, nz(load.getReadHours()) + delta));
            loadRepository.save(load);
            rec.setConductedHours(adjustedHours);
        }
        rec.setAdjustedHours(adjustedHours);
        rec.setNote(note);
        rec.setChangedBy(changedBy);
        return repository.save(rec);
    }

    @Transactional
    public void createMonthlyRecordsForLoad(TeacherLoad load) {
        if (!repository.findByTeacherLoadId(load.getId()).isEmpty()) return;
        for (int month = 1; month <= 12; month++) {
            repository.save(MonthlyRecord.builder()
                    .teacherLoad(load)
                    .month(month)
                    .year(load.getAcademicYear())
                    .hours(0)
                    .build());
        }
    }

    @Transactional
    public void initializeMonthlyRecordsForAllLoads() {
        int created = 0;
        for (TeacherLoad load : loadRepository.findAll()) {
            if (repository.findByTeacherLoadId(load.getId()).isEmpty()) {
                createMonthlyRecordsForLoad(load);
                created++;
            }
        }
        log.info("Created monthly records for {} teacher loads", created);
    }

    @Transactional
    public void recalculateHoursForLoad(Long loadId) {
        TeacherLoad load = loadRepository.findById(loadId).orElse(null);
        if (load == null) return;
        recalculateHoursForLoad(load, scheduleRepository.findByTeacherLoadId(loadId));
    }

    @Transactional
    public void recalculateHoursForLoad(TeacherLoad load, List<Schedule> schedulesForLoad) {
        if (load.getAcademicYear() == null) return;

        List<MonthlyRecord> records = repository.findByTeacherLoadId(load.getId());
        if (records.isEmpty()) {
            createMonthlyRecordsForLoad(load);
            records = repository.findByTeacherLoadId(load.getId());
        }
        Map<Integer, MonthlyRecord> byMonth = new HashMap<>();
        for (MonthlyRecord r : records) byMonth.putIfAbsent(r.getMonth(), r);

        Set<String> skipped = new HashSet<>();
        List<Long> ids = schedulesForLoad.stream().map(Schedule::getId).toList();
        if (!ids.isEmpty()) {
            for (LessonInstance li : instanceRepository.findByScheduleIdIn(ids)) {
                if (li.getStatus() == LessonInstance.Status.CANCELLED
                        || li.getStatus() == LessonInstance.Status.INDEPENDENT_WORK) {
                    skipped.add(li.getSchedule().getId() + "_" + li.getLessonDate());
                }
            }
        }

        int academicYearStart = load.getAcademicYear();
        for (int month = 1; month <= 12; month++) {
            int calendarYear = month >= 9 ? academicYearStart : academicYearStart + 1;
            MonthlyRecord rec = byMonth.get(month);
            if (rec != null) {
                rec.setHours(plannedHoursInMonth(schedulesForLoad, skipped, calendarYear, month, academicYearStart));
                repository.save(rec);
            }
        }
    }

    private int plannedHoursInMonth(List<Schedule> schedules, Set<String> skipped, int calendarYear, int month,
                                    int academicYear) {
        int hours = 0;
        for (LocalDate d = LocalDate.of(calendarYear, month, 1); d.getMonthValue() == month; d = d.plusDays(1)) {
            if (AcademicYearUtil.isVacation(d) || !AcademicYearUtil.isWorkDay(d)) continue;
            int week = AcademicYearUtil.academicWeek(d, academicYear);
            int semester = AcademicYearUtil.semesterOfDate(d);
            for (Schedule s : schedules) {
                if (!ScheduleOccurrence.occursOn(s, d, week, semester)) continue;
                if (skipped.contains(s.getId() + "_" + d)) continue;
                hours += HourAccountingService.pairHours(s);
            }
        }
        return hours;
    }

    private static int academicOrder(Integer month) {
        int m = month == null ? 0 : month;
        return m >= 9 ? m - 9 : m + 3;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
