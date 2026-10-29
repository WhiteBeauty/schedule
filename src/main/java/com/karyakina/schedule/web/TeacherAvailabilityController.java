package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.TeacherAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/teacher-availability")
@RequiredArgsConstructor
public class TeacherAvailabilityController {

    private final TeacherAvailabilityService service;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Long tarificationId,
                                  @RequestParam(required = false) Integer semester,
                                  Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(service.list(tarificationId, semester));
    }

    @PutMapping("/{teacherId}")
    public ResponseEntity<?> save(@PathVariable Long teacherId, @RequestBody Map<String, Object> body,
                                  Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        try {
            return ResponseEntity.ok(service.save(teacherId, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean isAdmin(Authentication auth) {
        if (auth == null) return false;
        User user = userRepository.findByEmail(auth.getName()).orElse(null);
        return user != null && user.getRole() == User.Role.ADMIN;
    }
}
