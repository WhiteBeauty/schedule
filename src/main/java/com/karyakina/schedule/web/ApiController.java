package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.*;
import com.karyakina.schedule.dto.ProductivityDto;
import com.karyakina.schedule.dto.SubstitutionInfoDto;
import com.karyakina.schedule.dto.TeacherProfileDto;
import com.karyakina.schedule.dto.TimeSyncDto;
import com.karyakina.schedule.repository.*;
import com.karyakina.schedule.service.*;
import com.karyakina.schedule.util.AcademicYearUtil;
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
    private final MonthlyRecordService monthlyRecordService;
    private final ScheduleService scheduleService;
    private final TeacherLoadRepository loadRepository;
    private final TeacherRepository teacherRepository;
    private final StudyGroupRepository groupRepository;
    private final ClassroomRepository classroomRepository;
    private final LessonInstanceRepository lessonInstanceRepository;
    private final com.karyakina.schedule.service.MonthScheduleService monthScheduleService;
    private final SettingsService settingsService;
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
    private final ScheduleExportService scheduleExportService;
    private final HourAccountingService hourAccountingService;
    private final PlacementAuditService placementAuditService;
    private final SubstitutionService substitutionService;
    private final NotificationService notificationService;
    private final ScheduleChangeNotifier scheduleChangeNotifier;
    private final LoadBalanceService loadBalanceService;
    private final SickLeaveReschedulingService sickLeaveReschedulingService;
    private final TarificationRepository tarificationRepository;

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
            @RequestParam(required = false) Integer year,
            Authentication authentication) {
        if (year == null) { year = AcademicYearUtil.getCurrentAcademicYearStart(); }
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

    @GetMapping("/loads/reconciliation")
    public ResponseEntity<List<Map<String, Object>>> loadsReconciliation(
            @RequestParam(required = false) Integer year,
            Authentication authentication) {
        if (year == null) { year = AcademicYearUtil.getCurrentAcademicYearStart(); }
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
        return loadRepository.findById(id)
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

        TeacherLoad updated = teacherLoadService.updateReadHours(id, hours, "admin:" + adminDisplayName(user));

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
        teachers.forEach(t -> {
            if (t.getUser() != null && t.getEmail() == null) {
                t.setEmail(t.getUser().getEmail());
            }
        });
        return ResponseEntity.ok(teachers);
    }

    @PostMapping("/teachers")
    public ResponseEntity<?> createTeacher(@RequestBody Map<String, Object> body, Authentication authentication) {
        User caller = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (caller.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        String email = (String) body.get("email");
        String fullName = (String) body.get("fullName");
        String department = (String) body.get("department");
        String position = (String) body.get("position");
        if (!(body.get("rate") instanceof Number)) {
            return ResponseEntity.badRequest().body("Укажите ставку");
        }
        Double rate = ((Number) body.get("rate")).doubleValue();
        String phone = (String) body.get("phone");
        String password = (String) body.get("password");

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        Teacher teacher = new Teacher();
        teacher.setUser(user);
        teacher.setFullName(fullName);
        teacher.setDepartment(department);
        teacher.setPosition(position);
        teacher.setRate(rate);
        teacher.setPhone(phone);

        if (password != null && !password.isEmpty()) {
            user.setPassword(passwordEncoder.encode(password));
            userRepository.save(user);
        }

        teacher = teacherRepository.save(teacher);
        return ResponseEntity.ok(teacher);
    }

    @PutMapping("/teachers/{id}")
    public ResponseEntity<?> updateTeacher(@PathVariable Long id,
                                            @RequestBody Map<String, Object> body,
                                            Authentication authentication) {
        User caller = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (caller.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        Teacher teacher = teacherRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + id));

        String fullName = (String) body.get("fullName");
        String department = (String) body.get("department");
        String position = (String) body.get("position");
        if (!(body.get("rate") instanceof Number)) {
            return ResponseEntity.badRequest().body("Укажите ставку");
        }
        Double rate = ((Number) body.get("rate")).doubleValue();
        String phone = (String) body.get("phone");
        String password = (String) body.get("password");

        teacher.setFullName(fullName);
        teacher.setDepartment(department);
        teacher.setPosition(position);
        teacher.setRate(rate);
        teacher.setPhone(phone);

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
                                                            @RequestParam(required = false) Integer year) {
        if (year == null) { year = AcademicYearUtil.getCurrentAcademicYearStart(); }
        return ResponseEntity.ok(teacherLoadService.buildProfile(id, year));
    }

    @GetMapping("/groups")
    public ResponseEntity<List<StudyGroup>> groups() {
        return ResponseEntity.ok(groupRepository.findAll());
    }

    @GetMapping("/disciplines")
    public ResponseEntity<List<Discipline>> disciplines(@RequestParam(required = false) Long groupId) {
        if (groupId == null) {
            return ResponseEntity.ok(disciplineRepository.findAll());
        }
        List<Discipline> groupDisciplines = loadRepository.findByGroupId(groupId).stream()
                .map(com.karyakina.schedule.domain.TeacherLoad::getDiscipline)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(java.util.Comparator.comparing(Discipline::getName))
                .toList();
        return ResponseEntity.ok(groupDisciplines);
    }

    @GetMapping("/classrooms")
    public ResponseEntity<List<Classroom>> classrooms() {
        return ResponseEntity.ok(classroomRepository.findAll());
    }

    @PostMapping("/classrooms")
    public ResponseEntity<?> createClassroom(@RequestBody Classroom payload, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        if (payload.getName() == null || payload.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Название аудитории обязательно"));
        }
        String name = payload.getName().trim();
        if (classroomRepository.findByNameIgnoreCase(name).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Аудитория с таким названием уже существует"));
        }
        Classroom classroom = Classroom.builder()
                .name(name)
                .capacity(payload.getCapacity())
                .allowedDisciplines(payload.getAllowedDisciplines() == null
                        ? new ArrayList<>() : new ArrayList<>(payload.getAllowedDisciplines()))
                .build();
        return ResponseEntity.ok(classroomRepository.save(classroom));
    }

    @PutMapping("/classrooms/{id}")
    public ResponseEntity<?> updateClassroom(@PathVariable Long id, @RequestBody Classroom payload,
                                              Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        return classroomRepository.findById(id).map(classroom -> {
            if (payload.getName() != null && !payload.getName().isBlank()) {
                classroom.setName(payload.getName().trim());
            }
            classroom.setCapacity(payload.getCapacity());
            classroom.setAllowedDisciplines(payload.getAllowedDisciplines() == null
                    ? new ArrayList<>() : new ArrayList<>(payload.getAllowedDisciplines()));
            return ResponseEntity.ok(classroomRepository.save(classroom));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/classrooms/{id}")
    public ResponseEntity<Void> deleteClassroom(@PathVariable Long id, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        if (!classroomRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        classroomRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

@GetMapping("/productivity")
    public ResponseEntity<List<ProductivityDto>> productivity(@RequestParam(required = false) Integer year) {
        if (year == null) { year = AcademicYearUtil.getCurrentAcademicYearStart(); }
        return ResponseEntity.ok(teacherLoadService.calculateProductivity(year));
    }

    @GetMapping("/monthly/{loadId}")
    public ResponseEntity<List<MonthlyRecord>> monthlyByLoad(@PathVariable Long loadId) {
        return ResponseEntity.ok(monthlyRecordService.findByLoad(loadId));
    }

    @GetMapping("/monthly-records")
    public ResponseEntity<List<Map<String, Object>>> getMonthlyRecords(
            @RequestParam(required = false) Integer year,
            Authentication authentication) {
        if (year == null) { year = AcademicYearUtil.getCurrentAcademicYearStart(); }
        try {
            User user = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            List<TeacherLoad> loads;
            if (user.getRole() == User.Role.ADMIN) {
                loads = loadRepository.findByAcademicYear(year);
            } else {
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
                    item.put("plannedHours", rec.getHours());
                    item.put("conductedHours", rec.getConductedHoursOrZero());
                    item.put("adjustedHours", rec.getAdjustedHours());
                    item.put("note", rec.getNote());
                    item.put("changedBy", rec.getChangedBy());
                    item.put("teacherId", load.getTeacher() != null ? load.getTeacher().getId() : null);
                    item.put("teacherName", load.getTeacher() != null ? load.getTeacher().getFullName() : "Не назначен");
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
                                                        @RequestBody Map<String, Object> body,
                                                        Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        Object raw = body.get("adjustedHours");
        Integer adjusted = raw == null || raw.toString().isBlank() ? null : Integer.valueOf(raw.toString());
        String note = body.get("note") != null ? body.get("note").toString() : "";
        return ResponseEntity.ok(monthlyRecordService.adjust(recordId, adjusted, note, "admin:" + adminDisplayName(user)));
    }

    @GetMapping("/monthly/export/csv")
    public ResponseEntity<byte[]> exportMonthlyCsv(
            @RequestParam(required = false) Integer year,
            Authentication authentication) {
        if (year == null) { year = AcademicYearUtil.getCurrentAcademicYearStart(); }
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
        csv.append("\uFEFF");
        csv.append("Преподаватель;Дисциплина;Группа;Месяц;Год;План по расписанию;Проведено;Скорректировано;Примечание;Кем изменено\n");

        String[] monthNames = {"", "январь", "февраль", "март", "апрель", "май", "июнь",
                                "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"};

        loads.forEach(load -> {
            List<MonthlyRecord> records = monthlyRecordService.findAggregatedByLoad(load.getId());
            records.forEach(rec -> {
                String monthStr = rec.getMonth() > 0 && rec.getMonth() < monthNames.length
                    ? monthNames[rec.getMonth()]
                    : String.valueOf(rec.getMonth());
                int calendarYear = rec.getMonth() >= 9 ? rec.getYear() : rec.getYear() + 1;
                String row = String.format("%s;%s;%s;%s;%d;%d;%d;%s;%s;%s\n",
                    load.getTeacher() != null ? load.getTeacher().getFullName() : "Не назначен",
                    load.getDiscipline().getName(),
                    load.getGroup().getName(),
                    monthStr,
                    calendarYear,
                    rec.getHours() != null ? rec.getHours() : 0,
                    rec.getConductedHoursOrZero(),
                    rec.getAdjustedHours() != null ? String.valueOf(rec.getAdjustedHours()) : "",
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

    @GetMapping("/schedule/export/month")
    public ResponseEntity<byte[]> exportMonthSchedule(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) String orgName,
            @RequestParam(required = false) String directorName,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        if (month < 1 || month > 12) {
            return ResponseEntity.badRequest().build();
        }
        try {
            byte[] bytes = scheduleExportService.exportMonth(year, month, orgName, directorName);
            String filename = "raspisanie_" + year + "_" + (month < 10 ? "0" + month : month) + ".xlsx";
            return ResponseEntity.ok()
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment;filename=" + filename)
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(("Не удалось сформировать файл: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @GetMapping("/hours/sverka")
    public ResponseEntity<List<HourAccountingService.LoadHoursSummary>> hoursSverka(
            @RequestParam(required = false) Integer academicYear,
            @RequestParam(required = false) Integer semester,
            @RequestParam(required = false) Long teacherId,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        Long effectiveTeacherId = teacherId;
        if (user.getRole() != User.Role.ADMIN) {
            if (user.getTeacher() == null) {
                return ResponseEntity.ok(List.of());
            }
            effectiveTeacherId = user.getTeacher().getId();
        }
        return ResponseEntity.ok(hourAccountingService.getSummary(academicYear, semester, effectiveTeacherId));
    }

    @GetMapping("/hours/sverka/week")
    public ResponseEntity<List<HourAccountingService.PeriodHoursSummary>> hoursSverkaWeek(
            @RequestParam(required = false) Integer academicYear,
            @RequestParam(required = false) Long teacherId,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        Long effectiveTeacherId = teacherId;
        if (user.getRole() != User.Role.ADMIN) {
            if (user.getTeacher() == null) {
                return ResponseEntity.ok(List.of());
            }
            effectiveTeacherId = user.getTeacher().getId();
        }
        return ResponseEntity.ok(hourAccountingService.getWeeklySummary(academicYear, effectiveTeacherId));
    }

    @GetMapping("/hours/sverka/month")
    public ResponseEntity<List<HourAccountingService.PeriodHoursSummary>> hoursSverkaMonth(
            @RequestParam(required = false) Integer academicYear,
            @RequestParam(required = false) Long teacherId,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        Long effectiveTeacherId = teacherId;
        if (user.getRole() != User.Role.ADMIN) {
            if (user.getTeacher() == null) {
                return ResponseEntity.ok(List.of());
            }
            effectiveTeacherId = user.getTeacher().getId();
        }
        return ResponseEntity.ok(hourAccountingService.getMonthlySummary(academicYear, effectiveTeacherId));
    }

    @GetMapping("/hours/placement-audit")
    public ResponseEntity<?> placementAudit(
            @RequestParam(required = false) Integer academicYear,
            @RequestParam int month,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        if (month < 1 || month > 12) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(placementAuditService.auditMonth(academicYear, month));
    }

    @PostMapping("/admin/teachers")
    public ResponseEntity<?> createTeacherByAdmin(@RequestBody Map<String, Object> body, Authentication authentication) {
        User caller = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (caller.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
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

    @GetMapping("/schedule")
    public ResponseEntity<List<TeacherLoad>> getSchedule(
            @RequestParam(required = false) Integer year,
            Authentication authentication) {
        if (year == null) { year = AcademicYearUtil.getCurrentAcademicYearStart(); }
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(teacherLoadService.findByYear(year));
    }

    @GetMapping("/schedule/progress")
    public ResponseEntity<Map<Long, HourAccountingService.LoadProgress>> scheduleProgress(
            @RequestParam(required = false) Integer year,
            Authentication authentication) {
        if (year == null) { year = AcademicYearUtil.getCurrentAcademicYearStart(); }
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(hourAccountingService.progressToDate(year));
    }

    @PostMapping("/schedule")
    public ResponseEntity<?> createScheduleEntry(
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
            applySemesterHours(load, body);
            load.setReadHours(0);
            load.setAcademicYear(academicYear);
            // без тарификации запись не попадёт в автосоставление
            load.setTarification(tarificationRepository.findByAcademicYearOrderByImportedAtDesc(academicYear)
                    .stream().findFirst().orElse(null));

            TeacherLoad savedLoad = loadRepository.save(load);

            monthlyRecordService.createMonthlyRecordsForLoad(savedLoad);

            return ResponseEntity.ok(savedLoad);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", "Не удалось создать запись: " + e.getMessage()));
        }
    }

    @PutMapping("/schedule/{id}")
    public ResponseEntity<?> updateScheduleEntry(
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

        if (body.get("readHours") != null && !body.get("readHours").toString().isBlank()) {
            int newRead = Integer.parseInt(body.get("readHours").toString());
            if (load.getReadHours() == null || newRead != load.getReadHours()) {
                teacherLoadService.updateReadHours(load.getId(), newRead, "admin:" + adminDisplayName(user));
                load = loadRepository.findById(id).orElse(load);
            }
        }
        Long newTeacherId = optionalLong(body.get("teacherId"));
        if (newTeacherId != null && (load.getTeacher() == null || !load.getTeacher().getId().equals(newTeacherId))) {
            Teacher newTeacher = teacherRepository.findById(newTeacherId).orElse(null);
            if (newTeacher == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Преподаватель не найден"));
            }
            List<String> conflicts = scheduleService.teacherSlotConflicts(load, newTeacher);
            if (!conflicts.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "У преподавателя " + newTeacher.getFullName()
                        + " в это время уже есть пары: " + String.join("; ", conflicts)));
            }
            load.setTeacher(newTeacher);
        }
        Long newGroupId = optionalLong(body.get("groupId"));
        if (newGroupId != null && !newGroupId.equals(load.getGroup().getId())) {
            if (!scheduleRepository.findByTeacherLoadId(load.getId()).isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "У записи уже есть пары в расписании, "
                        + "группу у неё поменять нельзя — удалите эти пары или заведите новую запись."));
            }
            StudyGroup group = groupRepository.findById(newGroupId).orElse(null);
            if (group != null) load.setGroup(group);
        }
        Long newDisciplineId = optionalLong(body.get("disciplineId"));
        if (newDisciplineId != null && !newDisciplineId.equals(load.getDiscipline().getId())) {
            disciplineRepository.findById(newDisciplineId).ifPresent(load::setDiscipline);
        }
        Long newYear = optionalLong(body.get("academicYear"));
        if (newYear != null) {
            load.setAcademicYear(newYear.intValue());
        }
        if (body.get("firstSemesterHours") != null || body.get("secondSemesterHours") != null) {
            applySemesterHours(load, body);
        } else if (body.get("plannedHours") != null) {
            load.setPlannedHours(Integer.valueOf(body.get("plannedHours").toString()));
        }
        if (body.containsKey("controlPointType1")) {
            load.setControlPointType1(blankToNull(body.get("controlPointType1")));
        }
        if (body.containsKey("controlPointType2")) {
            load.setControlPointType2(blankToNull(body.get("controlPointType2")));
        }

        return ResponseEntity.ok(loadRepository.save(load));
    }

    private void applySemesterHours(TeacherLoad load, Map<String, Object> body) {
        Long first = optionalLong(body.get("firstSemesterHours"));
        Long second = optionalLong(body.get("secondSemesterHours"));
        if (first == null && second == null) {
            int planned = Math.max(0, Integer.parseInt(body.get("plannedHours").toString()));
            boolean spring = AcademicYearUtil.getCurrentSemester() == 2;
            load.setFirstSemesterHours(spring ? 0 : planned);
            load.setSecondSemesterHours(spring ? planned : 0);
        } else {
            load.setFirstSemesterHours(first == null ? 0 : (int) Math.max(0, first));
            load.setSecondSemesterHours(second == null ? 0 : (int) Math.max(0, second));
        }
        load.setPlannedHours(load.getFirstSemesterHours() + load.getSecondSemesterHours());
    }

    private static Long optionalLong(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return Long.valueOf(value.toString().trim());
    }

    private static String blankToNull(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    @DeleteMapping("/schedule/{id}")
    public ResponseEntity<?> deleteScheduleEntry(
            @PathVariable Long id,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            adminDeletionService.deleteTeacherLoadCompletely(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "ошибка удаления" : e.getMessage()));
        }
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

    @PostMapping("/admin/wipe-all-except-admins")
    public ResponseEntity<?> wipeAllExceptAdmins(
            @RequestParam(name = "confirm", required = false) String confirm,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        if (!"WIPE".equals(confirm)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Требуется подтверждение: передайте параметр confirm=WIPE. "
                            + "Это удалит ВСЁ, кроме входа администраторов, без возможности отмены."));
        }
        try {
            return ResponseEntity.ok(adminDeletionService.wipeAllExceptAdmins());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/admin/cleanup/split-merged-names")
    public ResponseEntity<?> splitMergedGroupsAndDisciplines(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        try {
            return ResponseEntity.ok(adminDeletionService.splitMergedGroupsAndDisciplines());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

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
        if (body.containsKey("lunchStart") || body.containsKey("lunchEnd")) {
            Object startVal = body.get("lunchStart");
            Object endVal = body.get("lunchEnd");
            try {
                group.setLunchStart(startVal != null && !startVal.toString().isBlank()
                        ? java.time.LocalTime.parse(startVal.toString()) : null);
                group.setLunchEnd(endVal != null && !endVal.toString().isBlank()
                        ? java.time.LocalTime.parse(endVal.toString()) : null);
            } catch (Exception e) {
                return ResponseEntity.badRequest().build();
            }
            if ((group.getLunchStart() == null) != (group.getLunchEnd() == null)) {
                return ResponseEntity.badRequest().build();
            }
        }
        if (body.containsKey("maxWeeklyPairs")) {
            Object value = body.get("maxWeeklyPairs");
            group.setMaxWeeklyPairs(value == null || value.toString().isBlank()
                    ? null : Integer.valueOf(value.toString()));
        }

        return ResponseEntity.ok(groupRepository.save(group));
    }

    @GetMapping("/admin/settings/lunch")
    public ResponseEntity<?> getGlobalLunch(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        java.time.LocalTime[] window = settingsService.getGlobalLunchWindow();
        if (window == null) return ResponseEntity.ok(Map.of("configured", false));
        return ResponseEntity.ok(Map.of("configured", true, "start", window[0].toString(), "end", window[1].toString()));
    }

    @PostMapping("/admin/settings/lunch")
    public ResponseEntity<?> setGlobalLunch(@RequestBody Map<String, String> body, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        try {
            settingsService.setGlobalLunch(
                    java.time.LocalTime.parse(body.get("start")),
                    java.time.LocalTime.parse(body.get("end")));
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/admin/settings/lunch")
    public ResponseEntity<?> clearGlobalLunch(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        settingsService.clearGlobalLunch();
        return ResponseEntity.ok().build();
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

    @GetMapping("/pairs")
    public ResponseEntity<List<Schedule>> getPairs(
            @RequestParam(required = false) Integer year,
            Authentication authentication) {
        if (year == null) { year = AcademicYearUtil.getCurrentAcademicYearStart(); }
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

    @GetMapping("/pairs/substitutions")
    public ResponseEntity<List<SubstitutionInfoDto>> getActiveSubstitutions(
            @RequestParam(required = false) Integer year) {
        if (year == null) { year = AcademicYearUtil.getCurrentAcademicYearStart(); }
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(7);
        LocalDate to = today.plusDays(30);

        List<SubstitutionInfoDto> result = lessonInstanceRepository
                .findActiveReplacements(LessonInstance.Status.REPLACED, year, from, to).stream()
                .filter(li -> li.getSchedule() != null)
                .map(li -> new SubstitutionInfoDto(
                        li.getSchedule().getId(),
                        li.getLessonDate(),
                        li.getOriginalTeacher() != null ? li.getOriginalTeacher().getFullName() : null,
                        li.getActualTeacher() != null ? li.getActualTeacher().getFullName() : null))
                .toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/schedule/month")
    public ResponseEntity<com.karyakina.schedule.dto.MonthScheduleDto> getMonthSchedule(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) Integer academicYear,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) Long groupId) {
        if (month < 1 || month > 12) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(monthScheduleService.getMonth(year, month, academicYear, teacherId, groupId));
    }

    @GetMapping("/schedule/range")
    public ResponseEntity<com.karyakina.schedule.dto.MonthScheduleDto> getRangeSchedule(
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer academicYear,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) Long groupId) {
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 62) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(monthScheduleService.getRange(from, to, academicYear, teacherId, groupId, true));
    }

    @PostMapping("/pairs")
    public ResponseEntity<?> createPair(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        try {
            Object teacherLoadIdObj = body.get("teacherLoadId");
            Long teacherLoadId = null;

            if (teacherLoadIdObj != null) {
                teacherLoadId = Long.valueOf(teacherLoadIdObj.toString());
            } else {
                Object teacherIdObj = body.get("teacherId");
                Object groupIdObj = body.get("groupId");
                Object disciplineIdObj = body.get("disciplineId");

                if (teacherIdObj != null && groupIdObj != null && disciplineIdObj != null) {
                    Long teacherId = Long.valueOf(teacherIdObj.toString());
                    Long groupId = Long.valueOf(groupIdObj.toString());
                    Long disciplineId = Long.valueOf(disciplineIdObj.toString());

                    List<TeacherLoad> loads = loadRepository.findAll();
                    for (TeacherLoad load : loads) {
                        if (load.getTeacher() != null && load.getTeacher().getId().equals(teacherId) &&
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
            Integer semester = body.get("semester") != null && !body.get("semester").toString().isBlank()
                    ? Integer.valueOf(body.get("semester").toString()) : null;

            Schedule created = scheduleService.createSchedule(
                teacherLoadId,
                DayOfWeek.valueOf(dayOfWeek.toUpperCase()),
                LocalTime.parse(startTime),
                LocalTime.parse(endTime),
                classroom,
                academicWeek,
                academicYear,
                semester
            );
            try {
                scheduleChangeNotifier.pairCreated(created, adminDisplayName(user));
            } catch (Exception notifyEx) {
                notifyEx.printStackTrace();
            }
            try {
                monthlyRecordService.recalculateHoursForLoad(teacherLoadId);
            } catch (Exception syncEx) {
                syncEx.printStackTrace();
            }
            return ResponseEntity.ok(created);
        } catch (IllegalStateException validationError) {
            return ResponseEntity.badRequest().body(Map.of("error", validationError.getMessage()));
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
            try {
                monthlyRecordService.recalculateHoursForLoad(toDelete.getTeacherLoad().getId());
            } catch (Exception syncEx) {
                syncEx.printStackTrace();
            }
        }
        return ResponseEntity.ok().build();
    }

    @PutMapping("/pairs/{id}")
    public ResponseEntity<?> updatePair(
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
            try {
                monthlyRecordService.recalculateHoursForLoad(updated.getTeacherLoad().getId());
            } catch (Exception syncEx) {
                syncEx.printStackTrace();
            }

            return ResponseEntity.ok(updated);
        } catch (IllegalStateException validationError) {
            return ResponseEntity.badRequest().body(Map.of("error", validationError.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

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
    public ResponseEntity<?> createSickLeave(
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
            String reason = body.get("reason") != null && !body.get("reason").toString().isBlank()
                    ? body.get("reason").toString().trim() : "больничный";
            if (endDate.isBefore(startDate)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Дата окончания раньше даты начала"));
            }
            Integer academicYear = body.get("academicYear") != null && !body.get("academicYear").toString().isBlank()
                    ? Integer.valueOf(body.get("academicYear").toString())
                    : AcademicYearUtil.academicYearOf(startDate);

            SickLeave sickLeave = SickLeave.builder()
                    .teacher(teacher)
                    .startDate(startDate)
                    .endDate(endDate)
                    .reason(reason)
                    .academicYear(academicYear)
                    .build();

            SickLeave saved = sickLeaveRepository.save(sickLeave);

            com.karyakina.schedule.dto.RescheduleResultDTO result = null;
            try {
                result = sickLeaveReschedulingService.handleTeacherSickLeave(
                        saved.getTeacher().getId(), saved.getStartDate(), saved.getEndDate());
            } catch (Exception ex) {
                ex.printStackTrace();
                try {
                    notificationService.notifyAdmins(
                            Notification.Type.SUBSTITUTION_UNRESOLVED,
                            "Ошибка обработки отсутствия: " + saved.getTeacher().getFullName(),
                            "При обработке отсутствия (" + saved.getStartDate() + " — " + saved.getEndDate()
                                    + ") произошла техническая ошибка: " + ex.getClass().getSimpleName()
                                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "")
                                    + ". Требуется ручная проверка расписания и замены.",
                            null);
                } catch (Exception notifyEx) {
                    notifyEx.printStackTrace();
                }
            }

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("sickLeave", saved);
            response.put("result", result);
            return ResponseEntity.ok(response);
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
        SickLeave leave = sickLeaveRepository.findById(id).orElse(null);
        if (leave == null) {
            return ResponseEntity.notFound().build();
        }
        if (user.getRole() != User.Role.ADMIN && (user.getTeacher() == null
                || !user.getTeacher().getId().equals(leave.getTeacher().getId()))) {
            return ResponseEntity.status(403).build();
        }

        sickLeaveReschedulingService.revertSickLeave(id);

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

        scheduleService.autoDeductHours(AcademicYearUtil.getCurrentAcademicYearStart());
        return ResponseEntity.ok().build();
    }
}
