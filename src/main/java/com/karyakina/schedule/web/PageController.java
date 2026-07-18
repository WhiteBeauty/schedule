package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.DashboardDto;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.TeacherLoadService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class PageController {

    private final TeacherLoadService teacherLoadService;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;

    @GetMapping("/")
    public String redirect() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Authentication authentication,
                            @RequestParam(name = "year", required = false) Integer yearParam) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        model.addAttribute("isAdmin", user.getRole() == User.Role.ADMIN);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        model.addAttribute("defaultYear", AcademicYearUtil.getCurrentAcademicYearStart());

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        Teacher teacher = user.getTeacher();
        if (teacher == null) {
            if (user.getRole() == User.Role.ADMIN) {
                // Для ADMIN без учителя показываем пустую страницу
                model.addAttribute("teacher", null);
                model.addAttribute("year", year);
                model.addAttribute("productivity", null);
                model.addAttribute("loads", java.util.Collections.emptyList());
                model.addAttribute("totalDisciplines", 0);
                model.addAttribute("totalGroups", 0);
                model.addAttribute("totalHours1", 0);
                model.addAttribute("totalHours2", 0);
                model.addAttribute("totalYearHours", 0);
                model.addAttribute("totalRemaining", 0);
                model.addAttribute("totalRead", 0);
                model.addAttribute("profile", null);
                return "dashboard";
            } else {
                throw new RuntimeException("Teacher profile not found for user: " + email);
            }
        }

        DashboardDto dto = teacherLoadService.buildDashboard(teacher.getId(), year);

        model.addAttribute("teacher", dto);
        model.addAttribute("year", year);
        model.addAttribute("productivity", dto.getProductivity());
        model.addAttribute("loads", dto.getLoads());
        model.addAttribute("totalDisciplines", dto.getTotalDisciplines());
        model.addAttribute("totalGroups", dto.getTotalGroups());
        model.addAttribute("totalHours1", dto.getTotalHours1());
        model.addAttribute("totalHours2", dto.getTotalHours2());
        model.addAttribute("totalYearHours", dto.getTotalYearHours());
        model.addAttribute("totalRemaining", dto.getTotalRemaining());
        model.addAttribute("totalRead", dto.getTotalRead());
        model.addAttribute("profile", dto.getProfile());

        return "dashboard";
    }

    @GetMapping("/schedule")
    public String schedule(Model model, Authentication authentication,
                           @RequestParam(name = "year", required = false) Integer yearParam) {
        Teacher teacher = getTeacherFromAuth(authentication);
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        model.addAttribute("isAdmin", user.getRole() == User.Role.ADMIN);
        model.addAttribute("year", year);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        if (teacher != null) {
            model.addAttribute("teacherId", teacher.getId());
        } else {
            // ADMIN видит всех учителей
            model.addAttribute("teacherId", null);
        }
        return "schedule";
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

    @GetMapping("/curatorship")
    public String curatorship(Authentication authentication, Model model) {
        Teacher teacher = getTeacherFromAuth(authentication);
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        model.addAttribute("isAdmin", user.getRole() == User.Role.ADMIN);
        model.addAttribute("teacherId", teacher != null ? teacher.getId() : null);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        model.addAttribute("defaultYear", AcademicYearUtil.getCurrentAcademicYearStart());

        // Для админа показываем страницу управления кураторством
        if (user.getRole() == User.Role.ADMIN) {
            return "curatorship-admin";
        }

        return "curatorship";
    }

    @GetMapping("/teachers")
    public String teachers(Model model, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        model.addAttribute("isAdmin", user.getRole() == User.Role.ADMIN);
        return user.getRole() == User.Role.ADMIN ? "admin-teachers" : "teachers";
    }

    @GetMapping("/monthly")
    public String monthly(Authentication authentication, Model model,
                          @RequestParam(name = "year", required = false) Integer yearParam) {
        Teacher teacher = getTeacherFromAuth(authentication);
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        model.addAttribute("isAdmin", user.getRole() == User.Role.ADMIN);
        model.addAttribute("year", year);
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

        model.addAttribute("isAdmin", user.getRole() == User.Role.ADMIN);
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

        model.addAttribute("isAdmin", true);
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

        model.addAttribute("isAdmin", true);
        model.addAttribute("year", year);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "admin-teachers";
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

        model.addAttribute("isAdmin", true);
        model.addAttribute("year", year);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "admin-pairs";
    }

    @GetMapping("/admin/import")
    public String adminImport(Model model, Authentication authentication,
                              @RequestParam(name = "year", required = false) Integer yearParam) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return "redirect:/dashboard";
        }

        Integer year = yearParam != null ? yearParam : AcademicYearUtil.getCurrentAcademicYearStart();

        model.addAttribute("isAdmin", true);
        model.addAttribute("year", year);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "admin-import";
    }

    @GetMapping("/notifications")
    public String notifications(Model model, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        Teacher teacher = user.getTeacher();

        model.addAttribute("isAdmin", user.getRole() == User.Role.ADMIN);
        model.addAttribute("teacherId", teacher != null ? teacher.getId() : null);
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "notifications";
    }

    private Teacher getTeacherFromAuth(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        // ADMIN не требует учителя
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
