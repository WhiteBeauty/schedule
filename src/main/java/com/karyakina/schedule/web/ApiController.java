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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
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
    private final ScheduleService scheduleService;
    private final TeacherLoadRepository loadRepository;
    private final CuratorshipRepository curatorshipRepository;
    private final TeacherRepository teacherRepository;
    private final StudyGroupRepository groupRepository;
    private final DisciplineRepository disciplineRepository;
    private final MonthlyRecordRepository monthlyRecordRepository;
    private final SickLeaveRepository sickLeaveRepository;
    private final SubstitutionRequestRepository substitutionRequestRepository;
    private final ScheduleRepository scheduleRepository;
    private final AdminDeletionService adminDeletionService;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final SubstitutionService substitutionService;
    private final NotificationService notificationService;
    private final ScheduleChangeNotifier scheduleChangeNotifier;
    private final LoadBalanceService loadBalanceService;

    private String adminDisplayName(User user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        if (first != null && !first.isBlank()) {
            return last != null && !last.isBlank() ? first + " " + last : first;
        }
        return user.getUsername();
    }

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

    /** Сверка: план недели/месяца vs часы, уже поставленные в расписание (цель — разница 0). */
    @GetMapping("/loads/reconciliation")
    public ResponseEntity<List<Map<String, Object>>> loadsReconciliation(
            @RequestParam(defaultValue = "2026") Integer year,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Long teacherId = null;
        if (user.getRole() != User.Role.ADMIN) {
            Teacher teacher = user.getTeacher();
            if (teacher == null) {
                return ResponseEntity.ok(java.util.Collections.emptyList());
            }
            teacherId = teacher.getId();
        }
        return ResponseEntity.ok(loadBalanceService.buildReconciliation(year, teacherId));
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
                                                        @RequestBody Map<String, Integer> body,
                                                        Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        Integer hours = body.get("readHours");
        TeacherLoad before = loadRepository.findById(id).orElse(null);
        int oldHours = before != null && before.getReadHours() != null ? before.getReadHours() : 0;

        TeacherLoad updated = teacherLoadService.updateReadHours(id, hours);

        try {
            scheduleChangeNotifier.loadChanged(updated, oldHours, hours != null ? hours : 0, adminDisplayName(user));
        } catch (Exception notifyEx) {
            notifyEx.printStackTrace();
        }

        return ResponseEntity.ok(updated);
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

    @DeleteMapping("/teachers/{id}")
    public ResponseEntity<?> deleteTeacher(@PathVariable Long id, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            adminDeletionService.deleteTeacherCompletely(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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

    /** Получить все monthly records с информацией о нагрузке */
    @GetMapping("/monthly-records")
    public ResponseEntity<List<Map<String, Object>>> getMonthlyRecords(
            @RequestParam(defaultValue = "2026") Integer year,
            Authentication authentication) {
        try {
            User user = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            List<TeacherLoad> loads;
            if (user.getRole() == User.Role.ADMIN) {
                // Админ видит все нагрузки
                loads = loadRepository.findByAcademicYear(year);
            } else {
                // Преподаватель видит только свои
                Teacher teacher = user.getTeacher();
                if (teacher == null) {
                    return ResponseEntity.ok(new ArrayList<>());
                }
                loads = loadRepository.findByTeacherIdAndAcademicYear(teacher.getId(), year);
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (TeacherLoad load : loads) {
                List<MonthlyRecord> records = monthlyRecordService.findAggregatedByLoad(load.getId());
                for (MonthlyRecord rec : records) {
                    Map<String, Object> item = new java.util.HashMap<>();
                    item.put("id", rec.getId());
                    item.put("month", rec.getMonth());
                    item.put("year", rec.getYear());
                    item.put("hours", rec.getHours());
                    item.put("adjustedHours", rec.getAdjustedHours());
                    item.put("note", rec.getNote());
                    item.put("changedBy", rec.getChangedBy());
                    item.put("teacherId", load.getTeacher().getId());
                    item.put("teacherName", load.getTeacher().getFullName());
                    item.put("disciplineId", load.getDiscipline().getId());
                    item.put("disciplineName", load.getDiscipline().getName());
                    item.put("groupId", load.getGroup().getId());
                    item.put("groupName", load.getGroup().getName());
                    result.add(item);
                }
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(new ArrayList<>());
        }
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

    /** Получить все curatorship текущего авторизованного преподавателя */
    @GetMapping("/curatorships/my")
    public ResponseEntity<List<Curatorship>> getMyCuratorships(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getRole() == User.Role.ADMIN) {
            return ResponseEntity.ok(curatorshipService.findAll());
        }

        Teacher teacher = teacherRepository.findByUserId(user.getId()).orElse(null);
        if (teacher != null) {
            return ResponseEntity.ok(curatorshipService.findByTeacherId(teacher.getId()));
        }
        return ResponseEntity.ok(new ArrayList<>());
    }

    /** Получить curatorship по ID с проверкой прав */
    @GetMapping("/curatorships/by-id/{id}")
    public ResponseEntity<Curatorship> curatorshipById(@PathVariable Long id, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Curatorship curatorship = curatorshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Curatorship not found: " + id));

        if (user.getRole() != User.Role.ADMIN) {
            Teacher teacher = teacherRepository.findByUserId(user.getId()).orElse(null);
            if (teacher == null || !curatorship.getTeacher().getId().equals(teacher.getId())) {
                return ResponseEntity.status(403).build();
            }
        }

        return ResponseEntity.ok(curatorship);
    }

    @GetMapping("/monthly/export/csv")
    public ResponseEntity<byte[]> exportMonthlyCsv(
            @RequestParam(defaultValue = "2026") Integer year,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<TeacherLoad> loads;
        if (user.getRole() == User.Role.ADMIN) {
            loads = teacherLoadService.findByYear(year);
        } else {
            Teacher teacher = user.getTeacher();
            if (teacher == null) {
                return ResponseEntity.ok(new byte[0]);
            }
            loads = loadRepository.findByTeacherIdAndAcademicYear(teacher.getId(), year);
        }

        StringBuilder csv = new StringBuilder();
        // BOM для корректного отображения кириллицы в Excel
        csv.append("\uFEFF");
        csv.append("Преподаватель;Дисциплина;Группа;Месяц;Год;Часы (факт);Скорректировано;Примечание;Кем изменено\n");

        String[] monthNames = {"", "январь", "февраль", "март", "апрель", "май", "июнь",
                                "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"};

        loads.forEach(load -> {
            List<MonthlyRecord> records = monthlyRecordService.findByLoad(load.getId());
            records.forEach(rec -> {
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
            @RequestParam(defaultValue = "2026") Integer year,
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

            TeacherLoad savedLoad = loadRepository.save(load);

            // Создаём monthly records для всех 12 месяцев
            monthlyRecordService.createMonthlyRecordsForLoad(savedLoad);

            return ResponseEntity.ok(savedLoad);
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

        List<TeacherLoad> toDelete = loadRepository.findAllById(ids);
        for (TeacherLoad load : toDelete) {
            try {
                notificationService.notifyTeacher(load.getTeacher(), Notification.Type.LOAD_CHANGED,
                        "Удалена нагрузка: " + load.getDiscipline().getName(),
                        "Администратор " + adminDisplayName(user) + " удалил вашу нагрузку по дисциплине «"
                                + load.getDiscipline().getName() + "» у группы " + load.getGroup().getName() + ".",
                        null, "/time-sync", false);
            } catch (Exception notifyEx) {
                notifyEx.printStackTrace();
            }
            try {
                adminDeletionService.deleteTeacherLoadCompletely(load.getId());
            } catch (Exception deleteEx) {
                deleteEx.printStackTrace();
            }
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Полный снос расписания или снос по фильтру: только конкретный день недели,
     * только конкретная учебная неделя, либо пересечение обоих условий. Без параметров
     * dayOfWeek/academicWeek сносит весь учебный год целиком.
     */
    @PostMapping("/schedule/wipe")
    public ResponseEntity<?> wipeSchedule(
            @RequestParam(name = "academicYear", required = false) Integer academicYear,
            @RequestParam(name = "dayOfWeek", required = false) String dayOfWeekParam,
            @RequestParam(name = "academicWeek", required = false) Integer academicWeek,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        int year = academicYear != null ? academicYear : com.karyakina.schedule.util.AcademicYearUtil.getCurrentAcademicYearStart();
        DayOfWeek dayOfWeek = null;
        if (dayOfWeekParam != null && !dayOfWeekParam.isBlank()) {
            try {
                dayOfWeek = DayOfWeek.valueOf(dayOfWeekParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Некорректный день недели: " + dayOfWeekParam));
            }
        }

        try {
            return ResponseEntity.ok(adminDeletionService.wipeSchedule(year, dayOfWeek, academicWeek));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Curatorship Management ====================

    @PostMapping("/curatorships")
    public ResponseEntity<?> assignCuratorship(
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

            // Проверяем, есть ли уже такое кураторство у этого преподавателя для этой группы
            var existing = curatorshipRepository.findByTeacherIdAndGroupId(teacherId, groupId);
            if (existing.isPresent()) {
                return ResponseEntity.badRequest().body("Этот преподаватель уже является куратором данной группы");
            }

            // Проверяем, нет ли уже другого куратора у этой группы (один куратор на группу)
            var existingCurators = curatorshipRepository.findByGroupId(groupId);
            if (!existingCurators.isEmpty()) {
                return ResponseEntity.badRequest().body("У этой группы уже есть куратор. Одна группа может иметь только одного преподавателя-куратора.");
            }

            Curatorship curatorship = new Curatorship();
            curatorship.setTeacher(teacher);
            curatorship.setGroup(group);
            curatorship.setHours(hours);
            curatorship.setEvents(new ArrayList<>());
            curatorship.setLogs(new ArrayList<>());

            return ResponseEntity.ok(curatorshipRepository.save(curatorship));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Ошибка: " + e.getMessage());
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

        Curatorship curatorship = curatorshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Curatorship not found: " + id));

        // Проверка прав: админ или преподаватель, которому принадлежит это curatorship
        if (user.getRole() != User.Role.ADMIN) {
            Teacher teacher = teacherRepository.findByUserId(user.getId()).orElse(null);
            if (teacher == null || !curatorship.getTeacher().getId().equals(teacher.getId())) {
                return ResponseEntity.status(403).build();
            }
        }

        String event = body.get("event");
        if (event != null && !event.isEmpty()) {
            curatorship.getEvents().add(event);
            curatorship = curatorshipRepository.save(curatorship);
        }

        return ResponseEntity.ok(curatorship);
    }

    @DeleteMapping("/curatorships/{id}/event/{eventIdx}")
    public ResponseEntity<Curatorship> deleteEvent(
            @PathVariable Long id,
            @PathVariable Integer eventIdx,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Curatorship curatorship = curatorshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Curatorship not found: " + id));

        // Проверка прав
        if (user.getRole() != User.Role.ADMIN) {
            Teacher teacher = teacherRepository.findByUserId(user.getId()).orElse(null);
            if (teacher == null || !curatorship.getTeacher().getId().equals(teacher.getId())) {
                return ResponseEntity.status(403).build();
            }
        }

        if (eventIdx >= 0 && eventIdx < curatorship.getEvents().size()) {
            curatorship.getEvents().remove(eventIdx.intValue());
            curatorship = curatorshipRepository.save(curatorship);
        }

        return ResponseEntity.ok(curatorship);
    }

    @PutMapping("/curatorships/{id}/event")
    public ResponseEntity<Curatorship> updateEvent(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Curatorship curatorship = curatorshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Curatorship not found: " + id));

        // Проверка прав
        if (user.getRole() != User.Role.ADMIN) {
            Teacher teacher = teacherRepository.findByUserId(user.getId()).orElse(null);
            if (teacher == null || !curatorship.getTeacher().getId().equals(teacher.getId())) {
                return ResponseEntity.status(403).build();
            }
        }

        Object eventIdxObj = body.get("eventIdx");
        Integer eventIdx = eventIdxObj != null ? Integer.valueOf(eventIdxObj.toString()) : null;
        String event = body.get("event") != null ? body.get("event").toString() : null;

        if (eventIdx != null && eventIdx >= 0 && eventIdx < curatorship.getEvents().size() && event != null) {
            curatorship.getEvents().set(eventIdx.intValue(), event);
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

        Curatorship curatorship = curatorshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Curatorship not found: " + id));

        // Проверка прав
        if (user.getRole() != User.Role.ADMIN) {
            Teacher teacher = teacherRepository.findByUserId(user.getId()).orElse(null);
            if (teacher == null || !curatorship.getTeacher().getId().equals(teacher.getId())) {
                return ResponseEntity.status(403).build();
            }
        }

        String logEntry = body.get("logEntry");
        if (logEntry != null && !logEntry.isEmpty()) {
            curatorship.getLogs().add(logEntry);
            curatorship = curatorshipRepository.save(curatorship);
        }

        return ResponseEntity.ok(curatorship);
    }

    @DeleteMapping("/curatorships/{id}/log/{logIdx}")
    public ResponseEntity<Curatorship> deleteLog(
            @PathVariable Long id,
            @PathVariable Integer logIdx,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Curatorship curatorship = curatorshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Curatorship not found: " + id));

        // Проверка прав
        if (user.getRole() != User.Role.ADMIN) {
            Teacher teacher = teacherRepository.findByUserId(user.getId()).orElse(null);
            if (teacher == null || !curatorship.getTeacher().getId().equals(teacher.getId())) {
                return ResponseEntity.status(403).build();
            }
        }

        if (logIdx >= 0 && logIdx < curatorship.getLogs().size()) {
            curatorship.getLogs().remove(logIdx.intValue());
            curatorship = curatorshipRepository.save(curatorship);
        }

        return ResponseEntity.ok(curatorship);
    }

    @PutMapping("/curatorships/{id}/log")
    public ResponseEntity<Curatorship> updateLog(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Curatorship curatorship = curatorshipRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Curatorship not found: " + id));

        // Проверка прав
        if (user.getRole() != User.Role.ADMIN) {
            Teacher teacher = teacherRepository.findByUserId(user.getId()).orElse(null);
            if (teacher == null || !curatorship.getTeacher().getId().equals(teacher.getId())) {
                return ResponseEntity.status(403).build();
            }
        }

        Object logIdxObj = body.get("logIdx");
        Integer logIdx = logIdxObj != null ? Integer.valueOf(logIdxObj.toString()) : null;
        String logEntry = body.get("logEntry") != null ? body.get("logEntry").toString() : null;

        if (logIdx != null && logIdx >= 0 && logIdx < curatorship.getLogs().size() && logEntry != null) {
            curatorship.getLogs().set(logIdx.intValue(), logEntry);
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
    public ResponseEntity<?> deleteGroup(
            @PathVariable Long id,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            adminDeletionService.deleteGroupCompletely(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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
    public ResponseEntity<?> deleteDiscipline(
            @PathVariable Long id,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            adminDeletionService.deleteDisciplineCompletely(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Schedule (Pairs) Management ====================

    @GetMapping("/pairs")
    public ResponseEntity<List<Schedule>> getPairs(
            @RequestParam(defaultValue = "2026") Integer year,
            Authentication authentication) {
        try {
            User user = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            System.out.println("=== GET /pairs === User: " + user.getEmail() + ", Role: " + user.getRole() + ", Year: " + year);

            if (user.getRole() == User.Role.ADMIN) {
                List<Schedule> schedules = scheduleService.findByYear(year);
                System.out.println("ADMIN: Found " + schedules.size() + " schedules");
                return ResponseEntity.ok(schedules);
            } else {
                Teacher teacher = user.getTeacher();
                if (teacher == null) {
                    System.out.println("Teacher profile not found for user: " + user.getEmail());
                    return ResponseEntity.ok(new ArrayList<>());
                }
                System.out.println("Teacher ID: " + teacher.getId());
                List<Schedule> schedules = scheduleService.findByTeacherIdAndYear(teacher.getId(), year);
                System.out.println("TEACHER: Found " + schedules.size() + " schedules");
                return ResponseEntity.ok(schedules != null ? schedules : new ArrayList<>());
            }
        } catch (Exception e) {
            System.err.println("=== ERROR in /pairs ===");
            e.printStackTrace();
            return ResponseEntity.status(500).body(new ArrayList<>());
        }
    }

    @PostMapping("/pairs")
    public ResponseEntity<Schedule> createPair(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            // Пробуем получить teacherLoadId напрямую
            Object teacherLoadIdObj = body.get("teacherLoadId");
            Long teacherLoadId = null;

            if (teacherLoadIdObj != null) {
                teacherLoadId = Long.valueOf(teacherLoadIdObj.toString());
            } else {
                // Если teacherLoadId нет, пробуем найти по teacherId, groupId, disciplineId
                Object teacherIdObj = body.get("teacherId");
                Object groupIdObj = body.get("groupId");
                Object disciplineIdObj = body.get("disciplineId");

                if (teacherIdObj != null && groupIdObj != null && disciplineIdObj != null) {
                    Long teacherId = Long.valueOf(teacherIdObj.toString());
                    Long groupId = Long.valueOf(groupIdObj.toString());
                    Long disciplineId = Long.valueOf(disciplineIdObj.toString());

                    List<TeacherLoad> loads = loadRepository.findAll();
                    for (TeacherLoad load : loads) {
                        if (load.getTeacher().getId().equals(teacherId) &&
                            load.getGroup().getId().equals(groupId) &&
                            load.getDiscipline().getId().equals(disciplineId)) {
                            teacherLoadId = load.getId();
                            break;
                        }
                    }
                }
            }

            if (teacherLoadId == null) {
                return ResponseEntity.badRequest().body(null);
            }

            Object dayOfWeekObj = body.get("dayOfWeek");
            Object startTimeObj = body.get("startTime");
            Object endTimeObj = body.get("endTime");
            Object classroomObj = body.get("classroom");
            Object academicYearObj = body.get("academicYear");

            if (dayOfWeekObj == null || startTimeObj == null || endTimeObj == null || classroomObj == null || academicYearObj == null) {
                System.err.println("Missing required fields in request: " + body);
                return ResponseEntity.badRequest().body(null);
            }

            String dayOfWeek = dayOfWeekObj.toString();
            String startTime = startTimeObj.toString();
            String endTime = endTimeObj.toString();
            String classroom = classroomObj.toString();
            Integer academicWeek = body.containsKey("academicWeek") && body.get("academicWeek") != null ?
                Integer.valueOf(body.get("academicWeek").toString()) : null;
            Integer academicYear = Integer.valueOf(academicYearObj.toString());

            Schedule created = scheduleService.createSchedule(
                teacherLoadId,
                DayOfWeek.valueOf(dayOfWeek.toUpperCase()),
                LocalTime.parse(startTime),
                LocalTime.parse(endTime),
                classroom,
                academicWeek,
                academicYear
            );
            try {
                scheduleChangeNotifier.pairCreated(created, adminDisplayName(user));
            } catch (Exception notifyEx) {
                notifyEx.printStackTrace();
            }
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/pairs/{id}")
    public ResponseEntity<?> deletePair(
            @PathVariable Long id,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        Schedule toDelete = scheduleRepository.findById(id).orElse(null);
        try {
            adminDeletionService.deleteScheduleEntryCompletely(id);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if (toDelete != null) {
            try {
                scheduleChangeNotifier.pairDeleted(toDelete, adminDisplayName(user));
            } catch (Exception notifyEx) {
                notifyEx.printStackTrace();
            }
        }
        return ResponseEntity.ok().build();
    }

    @PutMapping("/pairs/{id}")
    public ResponseEntity<Schedule> updatePair(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            String dayOfWeek = body.get("dayOfWeek").toString();
            String startTime = body.get("startTime").toString();
            String endTime = body.get("endTime").toString();
            String classroom = body.get("classroom").toString();
            Integer academicWeek = body.containsKey("academicWeek") && body.get("academicWeek") != null ?
                Integer.valueOf(body.get("academicWeek").toString()) : null;

            Schedule before = scheduleRepository.findById(id).orElse(null);
            Schedule beforeSnapshot = before == null ? null : Schedule.builder()
                    .id(before.getId())
                    .teacherLoad(before.getTeacherLoad())
                    .dayOfWeek(before.getDayOfWeek())
                    .startTime(before.getStartTime())
                    .endTime(before.getEndTime())
                    .classroom(before.getClassroom())
                    .academicWeek(before.getAcademicWeek())
                    .academicYear(before.getAcademicYear())
                    .build();

            Schedule updated = scheduleService.updateSchedule(
                id,
                DayOfWeek.valueOf(dayOfWeek.toUpperCase()),
                LocalTime.parse(startTime),
                LocalTime.parse(endTime),
                classroom,
                academicWeek
            );

            if (beforeSnapshot != null) {
                try {
                    scheduleChangeNotifier.pairUpdated(beforeSnapshot, updated, adminDisplayName(user));
                } catch (Exception notifyEx) {
                    notifyEx.printStackTrace();
                }
            }

            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    // ==================== Sick Leave Management ====================

    @GetMapping("/sick-leaves")
    public ResponseEntity<List<SickLeave>> getSickLeaves(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getRole() == User.Role.ADMIN) {
            return ResponseEntity.ok(sickLeaveRepository.findAll());
        } else {
            Teacher teacher = user.getTeacher();
            if (teacher == null) {
                return ResponseEntity.ok(new ArrayList<>());
            }
            return ResponseEntity.ok(sickLeaveRepository.findByTeacherId(teacher.getId()));
        }
    }

    @PostMapping("/sick-leaves")
    public ResponseEntity<SickLeave> createSickLeave(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Long teacherId;
        if (user.getRole() == User.Role.ADMIN) {
            teacherId = Long.valueOf(body.get("teacherId").toString());
        } else {
            Teacher teacher = user.getTeacher();
            if (teacher == null) {
                return ResponseEntity.status(403).build();
            }
            teacherId = teacher.getId();
        }

        try {
            Teacher teacher = teacherRepository.findById(teacherId)
                    .orElseThrow(() -> new RuntimeException("Teacher not found: " + teacherId));
            LocalDate startDate = LocalDate.parse(body.get("startDate").toString());
            LocalDate endDate = LocalDate.parse(body.get("endDate").toString());
            // Причина: всегда передаём, даже если пустая
            String reason = body.get("reason") != null ? body.get("reason").toString() : "";
            Integer academicYear = Integer.valueOf(body.get("academicYear").toString());

            SickLeave sickLeave = SickLeave.builder()
                    .teacher(teacher)
                    .startDate(startDate)
                    .endDate(endDate)
                    .reason(reason)
                    .academicYear(academicYear)
                    .build();

            SickLeave saved = sickLeaveRepository.save(sickLeave);

            // МОДУЛЬ ФОРС-МАЖОРОВ: сразу ищем замену на все затронутые пары
            try {
                substitutionService.handleNewSickLeave(saved);
            } catch (Exception ex) {
                // Не блокируем регистрацию больничного, если подбор замены не удался,
                // но НЕ скрываем это от администрации — раньше исключение просто уходило
                // в лог сервера и снаружи выглядело так, будто вообще ничего не произошло.
                ex.printStackTrace();
                try {
                    notificationService.notifyAdmins(
                            Notification.Type.SUBSTITUTION_UNRESOLVED,
                            "Ошибка поиска замены: " + saved.getTeacher().getFullName(),
                            "При обработке отсутствия (" + saved.getStartDate() + " — " + saved.getEndDate()
                                    + ") произошла техническая ошибка, поиск замены не был завершён: "
                                    + ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : "")
                                    + ". Требуется ручная проверка расписания и замены.",
                            null);
                } catch (Exception notifyEx) {
                    notifyEx.printStackTrace();
                }
            }

            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/sick-leaves/{id}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Void> deleteSickLeave(
            @PathVariable Long id,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN && user.getRole() != User.Role.TEACHER) {
            return ResponseEntity.status(403).build();
        }

        // Сначала связанные заявки на замену и уведомления (FK)
        List<SubstitutionRequest> related = substitutionRequestRepository.findBySickLeaveId(id);
        for (SubstitutionRequest req : related) {
            if (req.getId() != null) {
                notificationRepository.deleteBySubstitutionRequestId(req.getId());
            }
        }
        substitutionRequestRepository.deleteBySickLeaveId(id);
        sickLeaveRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/auto-deduct")
    public ResponseEntity<Void> autoDeduct(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        scheduleService.autoDeductHours(2026);
        return ResponseEntity.ok().build();
    }
}
