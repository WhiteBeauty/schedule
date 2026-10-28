package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.LessonInstance;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.LessonInstanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/lesson-instances")
@RequiredArgsConstructor
public class LessonInstanceAdminController {

    private final LessonInstanceService lessonInstanceService;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final com.karyakina.schedule.service.HoursRecalculationService hoursRecalculationService;
    private final com.karyakina.schedule.service.SettingsService settingsService;
    private final com.karyakina.schedule.service.GroupWindowService groupWindowService;

    @PostMapping("/{id}/cancel")
    public ResponseEntity<LessonInstance> cancel(@PathVariable Long id,
                                                  @RequestBody(required = false) Map<String, String> body,
                                                  Authentication authentication) {
        User admin = requireAdmin(authentication);
        if (admin == null) return ResponseEntity.status(403).build();
        try {
            String note = body != null ? body.get("note") : null;
            LessonInstance updated = lessonInstanceService.cancelInstance(id, note, "admin:" + admin.getEmail());
            if (updated.getSchedule() != null && updated.getSchedule().getTeacherLoad() != null
                    && updated.getSchedule().getTeacherLoad().getGroup() != null) {
                try {
                    groupWindowService.closeWindows(java.util.Map.of(
                            updated.getSchedule().getTeacherLoad().getGroup().getId(),
                            java.util.Set.of(updated.getLessonDate())), updated.getAcademicYear(), null);
                } catch (Exception windowEx) {
                    windowEx.printStackTrace();
                }
            }
            if (settingsService.isPaceRecalculationEnabled() && updated.getSchedule() != null
                    && updated.getSchedule().getTeacherLoad() != null) {
                int semester = com.karyakina.schedule.util.AcademicYearUtil.semesterOfDate(updated.getLessonDate());
                if (semester > 0) {
                    try {
                        hoursRecalculationService.recalculate(updated.getAcademicYear(), semester,
                                java.util.List.of(updated.getSchedule().getTeacherLoad().getId()), false);
                    } catch (Exception recalcEx) {
                        recalcEx.printStackTrace();
                    }
                }
            }
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/independent-work")
    public ResponseEntity<LessonInstance> independentWork(@PathVariable Long id,
                                                            @RequestBody(required = false) Map<String, String> body,
                                                            Authentication authentication) {
        User admin = requireAdmin(authentication);
        if (admin == null) return ResponseEntity.status(403).build();
        try {
            String note = body != null ? body.get("note") : null;
            LessonInstance updated = lessonInstanceService.markIndependentWork(id, note, "admin:" + admin.getEmail());
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/assign-teacher")
    public ResponseEntity<LessonInstance> assignTeacher(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body,
                                                          Authentication authentication) {
        User admin = requireAdmin(authentication);
        if (admin == null) return ResponseEntity.status(403).build();
        try {
            Object teacherIdObj = body.get("teacherId");
            if (teacherIdObj == null) return ResponseEntity.badRequest().build();
            Long teacherId = Long.valueOf(teacherIdObj.toString());
            Teacher teacher = teacherRepository.findById(teacherId).orElse(null);
            if (teacher == null) return ResponseEntity.badRequest().build();

            String note = body.get("note") != null ? body.get("note").toString() : "Назначено администратором вручную";
            LessonInstance updated = lessonInstanceService.replaceInstance(id, teacher, note, "admin:" + admin.getEmail());
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private User requireAdmin(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null || user.getRole() != User.Role.ADMIN) return null;
        return user;
    }
}
