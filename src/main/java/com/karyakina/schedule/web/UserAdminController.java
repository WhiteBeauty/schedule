package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.AuditLog;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.AccountService;
import com.karyakina.schedule.service.UserAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;
    private final AccountService accountService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listUsers(Authentication authentication) {
        User actor = requireAdmin(authentication);
        if (actor == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(accountService.listUsers());
    }

    @GetMapping("/teachers-without-login")
    public ResponseEntity<List<Map<String, Object>>> teachersWithoutLogin(Authentication authentication) {
        User actor = requireAdmin(authentication);
        if (actor == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(accountService.teachersWithoutLogin());
    }

    // пустой список значит всем преподавателям у кого ещё нет входа
    @PostMapping("/teacher-logins")
    public ResponseEntity<?> createTeacherLogins(@RequestBody(required = false) Map<String, List<Long>> body,
                                                 Authentication authentication) {
        User actor = requireAdmin(authentication);
        if (actor == null) return ResponseEntity.status(403).build();
        List<Long> teacherIds = body == null ? List.of() : body.getOrDefault("teacherIds", List.of());
        return ResponseEntity.ok(accountService.createTeacherLogins(teacherIds, actor));
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<?> setPassword(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                         Authentication authentication) {
        User actor = requireAdmin(authentication);
        if (actor == null) return ResponseEntity.status(403).build();
        try {
            return ResponseEntity.ok(accountService.setPassword(id, body == null ? null : body.get("password"), actor));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/audit-log")
    public ResponseEntity<List<AuditLog>> auditLog(Authentication authentication) {
        User actor = requireAdmin(authentication);
        if (actor == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(userAdminService.findAuditLog());
    }

    @PostMapping("/{id}/promote")
    public ResponseEntity<?> promote(@PathVariable Long id, Authentication authentication) {
        User actor = requireAdmin(authentication);
        if (actor == null) return ResponseEntity.status(403).build();
        try {
            return ResponseEntity.ok(userAdminService.promoteToAdmin(id, actor));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/demote")
    public ResponseEntity<?> demote(@PathVariable Long id, Authentication authentication) {
        User actor = requireAdmin(authentication);
        if (actor == null) return ResponseEntity.status(403).build();
        try {
            return ResponseEntity.ok(userAdminService.demoteFromAdmin(id, actor));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private User requireAdmin(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getRole() == User.Role.ADMIN ? user : null;
    }
}
