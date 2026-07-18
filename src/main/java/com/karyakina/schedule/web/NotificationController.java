package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.Notification;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<Notification>> list(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getRole() == User.Role.ADMIN) {
            return ResponseEntity.ok(notificationService.findForAdmins());
        }
        Teacher teacher = user.getTeacher();
        if (teacher == null) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(notificationService.findForTeacher(teacher.getId()));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        long count;
        if (user.getRole() == User.Role.ADMIN) {
            count = notificationService.countUnreadForAdmins();
        } else {
            Teacher teacher = user.getTeacher();
            count = teacher == null ? 0 : notificationService.countUnreadForTeacher(teacher.getId());
        }
        return ResponseEntity.ok(Map.of("unread", count));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Notification> markRead(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(notificationService.markRead(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
