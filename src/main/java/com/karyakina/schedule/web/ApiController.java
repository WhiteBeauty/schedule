package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.dto.ProductivityDto;
import com.karyakina.schedule.dto.TeacherProfileDto;
import com.karyakina.schedule.dto.TimeSyncDto;
import com.karyakina.schedule.repository.*;
import com.karyakina.schedule.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final TimeSyncService timeSyncService;
    private final TeacherLoadService teacherLoadService;
    private final CuratorshipService curatorshipService;
    private final MonthlyRecordService monthlyRecordService;
    private final TeacherRepository teacherRepository;
    private final StudyGroupRepository groupRepository;
    private final DisciplineRepository disciplineRepository;
    private final MonthlyRecordRepository monthlyRecordRepository;

    @PostMapping("/time-sync")
    public ResponseEntity<TimeSyncDto> timeSync(@RequestBody Map<String, Long> body) {
        Long clientTime = body.getOrDefault("clientTime", System.currentTimeMillis());
        return ResponseEntity.ok(timeSyncService.checkSync(clientTime));
    }

    @GetMapping("/loads")
    public ResponseEntity<List<TeacherLoad>> loads(@RequestParam(defaultValue = "2024") Integer year) {
        return ResponseEntity.ok(teacherLoadService.findByYear(year));
    }

    @GetMapping("/loads/{id}")
    public ResponseEntity<TeacherLoad> loadById(@PathVariable Long id) {
        return teacherLoadService.findByYear(2024).stream()
                .filter(l -> l.getId().equals(id))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/loads/{id}/read-hours")
    public ResponseEntity<TeacherLoad> updateReadHours(@PathVariable Long id,
                                                        @RequestBody Map<String, Integer> body) {
        Integer hours = body.get("readHours");
        return ResponseEntity.ok(teacherLoadService.updateReadHours(id, hours));
    }

    @GetMapping("/teachers")
    public ResponseEntity<List<Teacher>> teachers() {
        return ResponseEntity.ok(teacherRepository.findAll());
    }

    @GetMapping("/teachers/{id}/profile")
    public ResponseEntity<TeacherProfileDto> teacherProfile(@PathVariable Long id,
                                                            @RequestParam(defaultValue = "2024") Integer year) {
        return ResponseEntity.ok(teacherLoadService.buildProfile(id, year));
    }

    @GetMapping("/groups")
    public ResponseEntity<List<StudyGroup>> groups() {
        return ResponseEntity.ok(groupRepository.findAll());
    }

    @GetMapping("/disciplines")
    public ResponseEntity<List<Discipline>> disciplines() {
        return ResponseEntity.ok(disciplineRepository.findAll());
    }

    @GetMapping("/curatorships")
    public ResponseEntity<List<Curatorship>> curatorships() {
        return ResponseEntity.ok(curatorshipService.findAll());
    }

    @GetMapping("/productivity")
    public ResponseEntity<List<ProductivityDto>> productivity(@RequestParam(defaultValue = "2024") Integer year) {
        return ResponseEntity.ok(teacherLoadService.calculateProductivity(year));
    }

    @GetMapping("/monthly/{loadId}")
    public ResponseEntity<List<MonthlyRecord>> monthlyByLoad(@PathVariable Long loadId) {
        return ResponseEntity.ok(monthlyRecordService.findByLoad(loadId));
    }

    @PostMapping("/monthly/{recordId}/adjust")
    public ResponseEntity<MonthlyRecord> adjustMonthly(@PathVariable Long recordId,
                                                        @RequestBody Map<String, Object> body) {
        Integer adjusted = (Integer) body.get("adjustedHours");
        String note = (String) body.getOrDefault("note", "");
        String changedBy = (String) body.getOrDefault("changedBy", "admin");
        return ResponseEntity.ok(monthlyRecordService.adjust(recordId, adjusted, note, changedBy));
    }

    @GetMapping("/curatorships/teacher/{teacherId}")
    public ResponseEntity<List<Curatorship>> curatorshipsByTeacher(@PathVariable Long teacherId) {
        return ResponseEntity.ok(curatorshipService.findByTeacherId(teacherId));
    }

    @GetMapping("/monthly/export/csv")
    public ResponseEntity<byte[]> exportMonthlyCsv(@RequestParam(defaultValue = "2024") Integer year) {
        List<TeacherLoad> loads = teacherLoadService.findByYear(year);

        StringBuilder csv = new StringBuilder();
        // BOM для корректного отображения кириллицы в Excel
        csv.append("\uFEFF");
        csv.append("Преподаватель;Дисциплина;Группа;Месяц;Год;Часы (факт);Скорректировано;Примечание;Кем изменено\n");

        String[] monthNames = {"", "январь", "февраль", "март", "апрель", "май", "июнь",
                                "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"};

        loads.forEach(load -> {
            load.getMonthlyRecords().forEach(rec -> {
                String monthStr = rec.getMonth() > 0 && rec.getMonth() < monthNames.length
                    ? monthNames[rec.getMonth()]
                    : String.valueOf(rec.getMonth());
                String row = String.format("%s;%s;%s;%s;%d;%d;%d;%s;%s\n",
                    load.getTeacher().getFullName(),
                    load.getDiscipline().getName(),
                    load.getGroup().getName(),
                    monthStr,
                    rec.getYear(),
                    rec.getHours(),
                    rec.getAdjustedHours() != null ? rec.getAdjustedHours() : 0,
                    rec.getNote() != null ? rec.getNote().replace(";", ",") : "",
                    rec.getChangedBy() != null ? rec.getChangedBy() : ""
                );
                csv.append(row);
            });
        });

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
            .header("Content-Type", "text/csv;charset=UTF-8")
            .header("Content-Disposition", "attachment;filename=monthly_hours_" + year + ".csv")
            .body(bytes);
    }
}
