package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.SubstitutionRequest;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.SubstitutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * МОДУЛЬ ОБРАБОТКИ ФОРС-МАЖОРОВ И АВТОМАТИЧЕСКОЙ ЗАМЕНЫ.
 * Подбор кандидата запускается автоматически при создании больничного
 * (см. ApiController#createSickLeave -> SubstitutionService#handleNewSickLeave).
 * Этот контроллер отвечает за отклик кандидата (подтвердить/отклонить) и
 * просмотр заявок.
 */
@RestController
@RequestMapping("/api/substitutions")
@RequiredArgsConstructor
public class SubstitutionController {

    private final SubstitutionService substitutionService;
    private final UserRepository userRepository;

    @GetMapping("/my-pending")
    public ResponseEntity<List<SubstitutionRequest>> myPending(Authentication authentication) {
        Teacher teacher = currentTeacher(authentication);
        if (teacher == null) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(substitutionService.findPendingForTeacher(teacher.getId()));
    }

    @GetMapping
    public ResponseEntity<List<SubstitutionRequest>> all(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(substitutionService.findAll());
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<SubstitutionRequest> accept(@PathVariable Long id, Authentication authentication) {
        Teacher teacher = currentTeacher(authentication);
        if (teacher == null) return ResponseEntity.status(403).build();
        try {
            return ResponseEntity.ok(substitutionService.acceptSubstitution(id, teacher));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<SubstitutionRequest> decline(@PathVariable Long id, Authentication authentication) {
        Teacher teacher = currentTeacher(authentication);
        if (teacher == null) return ResponseEntity.status(403).build();
        try {
            return ResponseEntity.ok(substitutionService.declineSubstitution(id, teacher));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private Teacher currentTeacher(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getTeacher();
    }
}
