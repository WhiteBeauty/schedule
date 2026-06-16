package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.dto.ProductivityDto;
import com.karyakina.schedule.dto.TeacherProfileDto;
import com.karyakina.schedule.dto.TimeSyncDto;
import com.karyakina.schedule.repository.*;
import com.karyakina.schedule.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final TeacherLoadRepository loadRepository;
    private final CuratorshipRepository curatorshipRepository;
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
    public ResponseEntity<List<TeacherLoad>> loads(
            @RequestParam(defaultValue = "2026") Integer year,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getRole() == User.Role.ADMIN) {
            return ResponseEntity.ok(teacherLoadService.findByYear(year));
        } else {
            Teacher teacher = user.getTeacher();
            if (teacher == null) {
                return ResponseEntity.ok(java.util.Collections.emptyList());
            }
            return ResponseEntity.ok(teacherLoadService.findByTeacherAndYear(teacher.getId(), year));
        }
    }

    @GetMapping("/loads/{id}")
    public ResponseEntity<TeacherLoad> loadById(@PathVariable Long id) {
        return teacherLoadService.findByYear(2026).stream()
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
                                                            @RequestParam(defaultValue = "2026") Integer year) {
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
    public ResponseEntity<List<Curatorship>> getAllCuratorships(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getRole() == User.Role.ADMIN) {
            return ResponseEntity.ok(curatorshipService.findAll());
        } else {
            Teacher teacher = teacherRepository.findByUserId(user.getId()).orElse(null);
            if (teacher != null) {
                return ResponseEntity.ok(curatorshipService.findByTeacherId(teacher.getId()));
            }
            return ResponseEntity.ok(new ArrayList<>());
        }
    }

    @GetMapping("/productivity")
    public ResponseEntity<List<ProductivityDto>> productivity(@RequestParam(defaultValue = "2026") Integer year) {
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

    @GetMapping("/curatorships/teacher/by-id/{id}")
    public ResponseEntity<Curatorship> curatorshipById(@PathVariable Long id) {
        return curatorshipRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
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

    // ==================== Schedule Management ====================

    @GetMapping("/schedule")
    public ResponseEntity<List<TeacherLoad>> getSchedule(
            @RequestParam(defaultValue = "2024") Integer year,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(teacherLoadService.findByYear(year));
    }

    @PostMapping("/schedule")
    public ResponseEntity<TeacherLoad> createScheduleEntry(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            Long teacherId = Long.valueOf(body.get("teacherId").toString());
            Long groupId = Long.valueOf(body.get("groupId").toString());
            Long disciplineId = Long.valueOf(body.get("disciplineId").toString());
            Integer plannedHours = Integer.valueOf(body.get("plannedHours").toString());
            Integer academicYear = Integer.valueOf(body.get("academicYear").toString());

            Teacher teacher = teacherRepository.findById(teacherId)
                    .orElseThrow(() -> new RuntimeException("Teacher not found: " + teacherId));
            StudyGroup group = groupRepository.findById(groupId)
                    .orElseThrow(() -> new RuntimeException("Group not found: " + groupId));
            Discipline discipline = disciplineRepository.findById(disciplineId)
                    .orElseThrow(() -> new RuntimeException("Discipline not found: " + disciplineId));

            TeacherLoad load = new TeacherLoad();
            load.setTeacher(teacher);
            load.setGroup(group);
            load.setDiscipline(discipline);
            load.setPlannedHours(plannedHours);
            load.setFirstSemesterHours(0);
            load.setSecondSemesterHours(0);
            load.setReadHours(0);
            load.setAcademicYear(academicYear);

            return ResponseEntity.ok(loadRepository.save(load));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/schedule/{id}")
    public ResponseEntity<TeacherLoad> updateScheduleEntry(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        TeacherLoad load = loadRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Load not found: " + id));

        if (body.containsKey("readHours")) {
            load.setReadHours(Integer.valueOf(body.get("readHours").toString()));
        }
        if (body.containsKey("plannedHours")) {
            load.setPlannedHours(Integer.valueOf(body.get("plannedHours").toString()));
        }
        if (body.containsKey("firstSemesterHours")) {
            load.setFirstSemesterHours(Integer.valueOf(body.get("firstSemesterHours").toString()));
        }
        if (body.containsKey("secondSemesterHours")) {
            load.setSecondSemesterHours(Integer.valueOf(body.get("secondSemesterHours").toString()));
        }
        if (body.containsKey("controlPointType1")) {
            load.setControlPointType1(body.get("controlPointType1").toString());
        }
        if (body.containsKey("controlPointType2")) {
            load.setControlPointType2(body.get("controlPointType2").toString());
        }

        return ResponseEntity.ok(loadRepository.save(load));
    }

    @DeleteMapping("/schedule/{id}")
    public ResponseEntity<Void> deleteScheduleEntry(
            @PathVariable Long id,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        loadRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/schedule/delete-batch")
    public ResponseEntity<Void> deleteScheduleBatch(
            @RequestBody List<Long> ids,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        loadRepository.deleteAllById(ids);
        return ResponseEntity.ok().build();
    }

    // ==================== Curatorship Management ====================

    @PostMapping("/curatorships")
    public ResponseEntity<Curatorship> assignCuratorship(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            Long teacherId = Long.valueOf(body.get("teacherId").toString());
            Long groupId = Long.valueOf(body.get("groupId").toString());
            Integer hours = body.containsKey("hours") ? Integer.valueOf(body.get("hours").toString()) : 36;

            Teacher teacher = teacherRepository.findById(teacherId)
                    .orElseThrow(() -> new RuntimeException("Teacher not found: " + teacherId));
            StudyGroup group = groupRepository.findById(groupId)
                    .orElseThrow(() -> new RuntimeException("Group not found: " + groupId));

            Curatorship curatorship = new Curatorship();
            curatorship.setTeacher(teacher);
            curatorship.setGroup(group);
            curatorship.setHours(hours);
            curatorship.setEvents(new ArrayList<>());
            curatorship.setLogs(new ArrayList<>());

            return ResponseEntity.ok(curatorshipRepository.save(curatorship));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/curatorships/{id}")
    public ResponseEntity<Curatorship> updateCuratorship(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        Curatorship curatorship = curatorshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Curatorship not found: " + id));

        if (body.containsKey("hours")) {
            curatorship.setHours(Integer.valueOf(body.get("hours").toString()));
        }
        if (body.containsKey("events")) {
            curatorship.setEvents((List<String>) body.get("events"));
        }
        if (body.containsKey("logs")) {
            curatorship.setLogs((List<String>) body.get("logs"));
        }
        if (body.containsKey("responsiblePerson")) {
            curatorship.setResponsiblePerson(body.get("responsiblePerson").toString());
        }

        return ResponseEntity.ok(curatorshipRepository.save(curatorship));
    }

    @DeleteMapping("/curatorships/{id}")
    public ResponseEntity<Void> deleteCuratorship(
            @PathVariable Long id,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        curatorshipRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/curatorships/{id}/event")
    public ResponseEntity<Curatorship> addEvent(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN && user.getRole() != User.Role.TEACHER) {
            return ResponseEntity.status(403).build();
        }

        Curatorship curatorship = curatorshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Curatorship not found: " + id));

        String event = body.get("event");
        if (event != null && !event.isEmpty()) {
            curatorship.getEvents().add(event);
            curatorship = curatorshipRepository.save(curatorship);
        }

        return ResponseEntity.ok(curatorship);
    }

    @PostMapping("/curatorships/{id}/log")
    public ResponseEntity<Curatorship> addLog(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN && user.getRole() != User.Role.TEACHER) {
            return ResponseEntity.status(403).build();
        }

        Curatorship curatorship = curatorshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Curatorship not found: " + id));

        String logEntry = body.get("logEntry");
        if (logEntry != null && !logEntry.isEmpty()) {
            curatorship.getLogs().add(logEntry);
            curatorship = curatorshipRepository.save(curatorship);
        }

        return ResponseEntity.ok(curatorship);
    }

    // ==================== Groups Management ====================

    @GetMapping("/admin/groups")
    public ResponseEntity<List<StudyGroup>> getAllGroups(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(groupRepository.findAll());
    }

    @PostMapping("/admin/groups")
    public ResponseEntity<StudyGroup> createGroup(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            StudyGroup group = new StudyGroup();
            group.setName(body.get("name").toString());
            group.setCourse(Integer.valueOf(body.get("course").toString()));
            group.setSpecialty(body.getOrDefault("specialty", "").toString());

            return ResponseEntity.ok(groupRepository.save(group));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/admin/groups/{id}")
    public ResponseEntity<StudyGroup> updateGroup(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        StudyGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Group not found: " + id));

        if (body.containsKey("name")) {
            group.setName(body.get("name").toString());
        }
        if (body.containsKey("course")) {
            group.setCourse(Integer.valueOf(body.get("course").toString()));
        }
        if (body.containsKey("specialty")) {
            group.setSpecialty(body.get("specialty").toString());
        }

        return ResponseEntity.ok(groupRepository.save(group));
    }

    @DeleteMapping("/admin/groups/{id}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable Long id,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        groupRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    // ==================== Disciplines Management ====================

    @GetMapping("/admin/disciplines")
    public ResponseEntity<List<Discipline>> getAllDisciplines(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(disciplineRepository.findAll());
    }

    @PostMapping("/admin/disciplines")
    public ResponseEntity<Discipline> createDiscipline(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            Discipline discipline = new Discipline();
            discipline.setName(body.get("name").toString());
            discipline.setCode(body.getOrDefault("code", "").toString());

            return ResponseEntity.ok(disciplineRepository.save(discipline));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/admin/disciplines/{id}")
    public ResponseEntity<Discipline> updateDiscipline(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        Discipline discipline = disciplineRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Discipline not found: " + id));

        if (body.containsKey("name")) {
            discipline.setName(body.get("name").toString());
        }
        if (body.containsKey("code")) {
            discipline.setCode(body.get("code").toString());
        }

        return ResponseEntity.ok(disciplineRepository.save(discipline));
    }

    @DeleteMapping("/admin/disciplines/{id}")
    public ResponseEntity<Void> deleteDiscipline(
            @PathVariable Long id,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        disciplineRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
