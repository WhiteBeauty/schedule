package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.DashboardMetricsDto;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.NotificationService;
import com.karyakina.schedule.service.OverviewService;
import com.karyakina.schedule.service.TeacherLoadService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

@Controller
@RequiredArgsConstructor
public class PageController {

    private final TeacherLoadService teacherLoadService;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final OverviewService overviewService;
    private final NotificationService notificationService;

    @GetMapping("/")
    public String redirect() {
        return "redirect:/dashboard";
    }

    private void addUserChrome(Model model, User user, String activeNav) {
        String name;
        if (user.getTeacher() != null) {
            name = user.getTeacher().getFullName();
        } else {
            String first = user.getFirstName() != null ? user.getFirstName() : "";
            String last = user.getLastName() != null ? user.getLastName() : "";
            name = (last + " " + first).trim();
            if (name.isEmpty()) name = user.getUsername();
        }
        model.addAttribute("currentUserName", name);
        model.addAttribute("currentUserInitials", initialsOf(name));
        model.addAttribute("currentUserRoleLabel", user.getRole() == User.Role.ADMIN ? "Администратор" : "Преподаватель");
        model.addAttribute("activeNav", activeNav);
        model.addAttribute("isAdmin", user.getRole() == User.Role.ADMIN);
    }

    private String initialsOf(String fullName) {
        if (fullName == null || fullName.isBlank()) return "??";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Authentication authentication,
                            @RequestParam(name = "year", required = false) Integer yearParam) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        addUserChrome(model, user, "dashboard");
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        model.addAttribute("defaultYear", AcademicYearUtil.getCurrentAcademicYearStart());
        model.addAttribute("year", year);
        model.addAttribute("currentSemester", AcademicYearUtil.getCurrentSemester());

        DashboardMetricsDto metrics = overviewService.buildDashboardMetrics(year);
        model.addAttribute("metrics", metrics);
        model.addAttribute("todayLabel", java.time.format.DateTimeFormatter
                .ofPattern("d MMMM, EEEE", new java.util.Locale("ru"))
                .format(LocalDate.now()));

        java.util.List<com.karyakina.schedule.domain.Notification> notifications = user.getRole() == User.Role.ADMIN
                ? notificationService.findForAdmins()
                : (user.getTeacher() != null ? notificationService.findForTeacher(user.getTeacher().getId()) : java.util.Collections.emptyList());
        model.addAttribute("notifications", notifications.stream().limit(4).toList());

        return "dashboard";
    }

    @GetMapping("/schedule")
    public String schedule(Model model, Authentication authentication,
                           @RequestParam(name = "year", required = false) Integer yearParam) {
        Teacher teacher = getTeacherFromAuth(authentication);
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        addUserChrome(model, user, "schedule");
        model.addAttribute("year", year);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        model.addAttribute("teacherId", teacher != null ? teacher.getId() : null);
        return "schedule";
    }

    @GetMapping("/teacher-load")
    public String teacherLoad(Model model, Authentication authentication,
                              @RequestParam(name = "year", required = false) Integer yearParam) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        addUserChrome(model, user, "teacher-load");
        model.addAttribute("year", year);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        model.addAttribute("summary", overviewService.buildTeacherLoadSummary(year));
        return "teacher-load";
    }

    @GetMapping("/import")
    public String importPage(Model model, Authentication authentication,
                             @RequestParam(name = "year", required = false) Integer yearParam,
                             @RequestParam(name = "type", required = false, defaultValue = "load") String type) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return "redirect:/dashboard";
        }

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        addUserChrome(model, user, "import");
        model.addAttribute("year", year);
        model.addAttribute("importType", "tarification".equals(type) ? "tarification" : "load");
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "import";
    }

    @GetMapping("/reports")
    public String reports(Model model, Authentication authentication,
                          @RequestParam(name = "year", required = false) Integer yearParam) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        addUserChrome(model, user, "reports");
        model.addAttribute("year", year);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        model.addAttribute("currentSemester", AcademicYearUtil.getCurrentSemester());
        model.addAttribute("departmentHours", overviewService.buildDepartmentHours(year));
        model.addAttribute("productivity", teacherLoadService.calculateProductivity(year));
        model.addAttribute("examsConducted", overviewService.countConductedExams(year));
        return "reports";
    }

    @GetMapping("/settings")
    public String settings(Model model, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        addUserChrome(model, user, "settings");
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "settings";
    }

    @GetMapping("/time-sync")
    public String timeSync(Authentication authentication, Model model) {
        Teacher teacher = getTeacherFromAuth(authentication);
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        model.addAttribute("isAdmin", user.getRole() == User.Role.ADMIN);
        model.addAttribute("teacherId", teacher != null ? teacher.getId() : null);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        model.addAttribute("defaultYear", AcademicYearUtil.getCurrentAcademicYearStart());
        return "time-sync";
    }

    @GetMapping("/schedule/month")
    public String scheduleMonth(Model model, Authentication authentication,
                                 @RequestParam(name = "year", required = false) Integer yearParam,
                                 @RequestParam(name = "month", required = false) Integer monthParam) {
        Teacher teacher = getTeacherFromAuth(authentication);
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        LocalDate today = LocalDate.now();
        int calendarYear = yearParam != null ? yearParam : today.getYear();
        int calendarMonth = monthParam != null ? monthParam : today.getMonthValue();

        addUserChrome(model, user, "settings");
        model.addAttribute("calendarYear", calendarYear);
        model.addAttribute("calendarMonth", calendarMonth);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        model.addAttribute("teacherId", teacher != null ? teacher.getId() : null);
        return "schedule-month";
    }

    @GetMapping("/curatorship")
    public String curatorship(Authentication authentication, Model model) {
        Teacher teacher = getTeacherFromAuth(authentication);
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        addUserChrome(model, user, "settings");
        model.addAttribute("teacherId", teacher != null ? teacher.getId() : null);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        model.addAttribute("defaultYear", AcademicYearUtil.getCurrentAcademicYearStart());

        if (user.getRole() == User.Role.ADMIN) {
            return "curatorship-admin";
        }

        return "curatorship";
    }

    @GetMapping("/teachers")
    public String teachers(Model model, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        addUserChrome(model, user, user.getRole() == User.Role.ADMIN ? "settings" : "teacher-load");
        model.addAttribute("defaultYear", AcademicYearUtil.getCurrentAcademicYearStart());
        model.addAttribute("year", AcademicYearUtil.getCurrentAcademicYearStart());
        return user.getRole() == User.Role.ADMIN ? "admin-teachers" : "teachers";
    }

    @GetMapping("/monthly")
    public String monthly(Authentication authentication, Model model,
                          @RequestParam(name = "year", required = false) Integer yearParam) {
        Teacher teacher = getTeacherFromAuth(authentication);
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        addUserChrome(model, user, "settings");
        model.addAttribute("year", year);
        model.addAttribute("defaultYear", year);
        model.addAttribute("teacherId", teacher != null ? teacher.getId() : null);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "monthly";
    }

    @GetMapping("/productivity")
    public String productivity(Authentication authentication, Model model,
                               @RequestParam(name = "year", required = false) Integer yearParam) {
        Teacher teacher = getTeacherFromAuth(authentication);
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        addUserChrome(model, user, "reports");
        model.addAttribute("year", year);
        model.addAttribute("teacherId", teacher != null ? teacher.getId() : null);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "productivity";
    }

    @GetMapping("/admin/schedule")
    public String adminSchedule(Model model, Authentication authentication,
                                @RequestParam(name = "year", required = false) Integer yearParam) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return "redirect:/dashboard";
        }

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        addUserChrome(model, user, "settings");
        model.addAttribute("year", year);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "admin-schedule";
    }

    @GetMapping("/admin/teachers")
    public String adminTeachers(Model model, Authentication authentication,
                                @RequestParam(name = "year", required = false) Integer yearParam) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return "redirect:/dashboard";
        }

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        addUserChrome(model, user, "teacher-load");
        model.addAttribute("year", year);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "admin-teachers";
    }

    @GetMapping("/admin/users")
    public String adminUsers(Model model, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return "redirect:/dashboard";
        }

        addUserChrome(model, user, "settings");
        model.addAttribute("currentUserId", user.getId());
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "admin-users";
    }

    @GetMapping("/admin/pairs")
    public String adminPairs(Model model, Authentication authentication,
                             @RequestParam(name = "year", required = false) Integer yearParam) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return "redirect:/dashboard";
        }

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        addUserChrome(model, user, "settings");
        model.addAttribute("year", year);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "admin-pairs";
    }

    @GetMapping("/admin/import")
    public String adminImport(Authentication authentication) {
        return "redirect:/import?type=load";
    }

    @GetMapping("/admin/tarification-import")
    public String adminTarificationImport(Authentication authentication) {
        return "redirect:/import?type=tarification";
    }

    @GetMapping("/notifications")
    public String notifications(Model model, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        Teacher teacher = user.getTeacher();

        addUserChrome(model, user, "notifications");
        model.addAttribute("teacherId", teacher != null ? teacher.getId() : null);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "notifications";
    }

    private Teacher getTeacherFromAuth(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        if (user.getRole() == User.Role.ADMIN) {
            return null;
        }

        Teacher teacher = user.getTeacher();
        if (teacher == null) {
            throw new RuntimeException("Teacher profile not found for user: " + email);
        }
        return teacher;
    }
}
