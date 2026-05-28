package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.DashboardDto;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.TeacherLoadService;
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
    public String dashboard(Model model, Authentication authentication) {
        String username = authentication.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        Teacher teacher = user.getTeacher();
        if (teacher == null) {
            // Если учитель не привязан, ищем по username (для демо)
            teacher = teacherRepository.findAll().stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No teacher found"));
        }

        Integer year = 2024;
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
                           @RequestParam(name = "year", defaultValue = "2024") Integer year) {
        Teacher teacher = getTeacherFromAuth(authentication);
        model.addAttribute("year", year);
        model.addAttribute("teacherId", teacher.getId());
        return "schedule";
    }

    @GetMapping("/time-sync")
    public String timeSync(Authentication authentication, Model model) {
        Teacher teacher = getTeacherFromAuth(authentication);
        model.addAttribute("teacherId", teacher.getId());
        return "time-sync";
    }

    @GetMapping("/curatorship")
    public String curatorship(Authentication authentication, Model model) {
        Teacher teacher = getTeacherFromAuth(authentication);
        model.addAttribute("teacherId", teacher.getId());
        return "curatorship";
    }

    @GetMapping("/teachers")
    public String teachers() {
        return "teachers";
    }

    @GetMapping("/monthly")
    public String monthly(Authentication authentication, Model model,
                          @RequestParam(name = "year", defaultValue = "2024") Integer year) {
        Teacher teacher = getTeacherFromAuth(authentication);
        model.addAttribute("year", year);
        model.addAttribute("teacherId", teacher.getId());
        return "monthly";
    }

    @GetMapping("/productivity")
    public String productivity(@RequestParam(name = "year", defaultValue = "2024") Integer year,
                               Model model) {
        model.addAttribute("year", year);
        return "productivity";
    }

    private Teacher getTeacherFromAuth(Authentication authentication) {
        String username = authentication.getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        Teacher teacher = user.getTeacher();
        if (teacher == null) {
            teacher = teacherRepository.findAll().stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No teacher found"));
        }
        return teacher;
    }
}
