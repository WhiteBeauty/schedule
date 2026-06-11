package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.dto.ProductivityDto;
import com.karyakina.schedule.dto.TeacherProfileDto;
import com.karyakina.schedule.dto.TimeSyncDto;
import com.karyakina.schedule.repository.*;
import com.karyakina.schedule.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

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
        List<Teacher> teachers = teacherRepository.findAll();
        // Загружаем email из User через Join
        teachers.forEach(t -> {
            if (t.getUser() != null && t.getEmail() == null) {
                t.setEmail(t.getUser().getEmail());
            }
        });
        return ResponseEntity.ok(teachers);
    }

    @PostMapping("/teachers")
    public ResponseEntity<Teacher> createTeacher(@RequestBody Map<String, Object> body) {
        String email = (String) body.get("email");
        String fullName = (String) body.get("fullName");
        String department = (String) body.get("department");
        String position = (String) body.get("position");
        Double rate = ((Number) body.get("rate")).doubleValue();
        String phone = (String) body.get("phone");
        String password = (String) body.get("password");

        // Check if user exists
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        // Create or update teacher profile
        Teacher teacher = new Teacher();
        teacher.setUser(user);
        teacher.setFullName(fullName);
        teacher.setDepartment(department);
        teacher.setPosition(position);
        teacher.setRate(rate);
        teacher.setPhone(phone);

        // Set password if provided
        if (password != null && !password.isEmpty()) {
            user.setPassword(passwordEncoder.encode(password));
            userRepository.save(user);
        }

        teacher = teacherRepository.save(teacher);
        return ResponseEntity.ok(teacher);
    }

    @PutMapping("/teachers/{id}")
    public ResponseEntity<Teacher> updateTeacher(@PathVariable Long id,
                                                  @RequestBody Map<String, Object> body) {
        Teacher teacher = teacherRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + id));

        String fullName = (String) body.get("fullName");
        String department = (String) body.get("department");
        String position = (String) body.get("position");
        Double rate = ((Number) body.get("rate")).doubleValue();
        String phone = (String) body.get("phone");
        String password = (String) body.get("password");

        teacher.setFullName(fullName);
        teacher.setDepartment(department);
        teacher.setPosition(position);
        teacher.setRate(rate);
        teacher.setPhone(phone);

        // Update password if provided
        if (password != null && !password.isEmpty()) {
            User user = teacher.getUser();
            if (user != null) {
                user.setPassword(passwordEncoder.encode(password));
                userRepository.save(user);
            }
        }

        teacher = teacherRepository.save(teacher);
        return ResponseEntity.ok(teacher);
    }

    @GetMapping("/teachers/{id}")
    public ResponseEntity<Teacher> getTeacher(@PathVariable Long id) {
        Teacher teacher = teacherRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + id));
        return ResponseEntity.ok(teacher);
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

    @PostMapping("/admin/teachers")
    public ResponseEntity<?> createTeacherByAdmin(@RequestBody Map<String, Object> body) {
        try {
            String email = (String) body.get("email");
            String username = (String) body.getOrDefault("username", email.split("@")[0]);
            String fullName = (String) body.get("fullName");
            String department = (String) body.get("department");
            String position = (String) body.get("position");
            Double rate = ((Number) body.get("rate")).doubleValue();
            String phone = (String) body.get("phone");
            String password = (String) body.get("password");
            String birthDateStr = (String) body.get("birthDate");

            if (userRepository.existsByEmail(email)) {
                return ResponseEntity.badRequest().body("Email уже зарегистрирован");
            }

            java.time.LocalDate birthDate = null;
            if (birthDateStr != null && !birthDateStr.isEmpty()) {
                birthDate = java.time.LocalDate.parse(birthDateStr);
            }

            String[] nameParts = fullName.trim().split("\\s+");
            String firstName = nameParts.length > 1 ? nameParts[1] : "";
            String lastName = nameParts[0];

            com.karyakina.schedule.domain.Teacher teacher = com.karyakina.schedule.domain.Teacher.builder()
                    .fullName(fullName)
                    .department(department)
                    .position(position)
                    .email(email)
                    .phone(phone)
                    .rate(rate)
                    .birthDate(birthDate)
                    .build();
            teacher = teacherRepository.save(teacher);

            com.karyakina.schedule.domain.User user = com.karyakina.schedule.domain.User.builder()
                    .username(username)
                    .email(email)
                    .password(passwordEncoder.encode(password))
                    .role(com.karyakina.schedule.domain.User.Role.TEACHER)
                    .teacher(teacher)
                    .firstName(firstName)
                    .lastName(lastName)
                    .phone(phone)
                    .birthDate(birthDate)
                    .build();
            userRepository.save(user);

            teacher.setUser(user);
            teacherRepository.save(teacher);

            return ResponseEntity.ok(teacher);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Ошибка: " + e.getMessage());
        }
    }
}
