package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.AfterSaveService;
import com.karyakina.schedule.service.VacancyService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/vacancies")
@RequiredArgsConstructor
@Slf4j
public class VacancyController {

    private final VacancyService vacancyService;
    private final AfterSaveService afterSaveService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Integer academicYear, Authentication auth) {
        if (admin(auth) == null) return ResponseEntity.status(403).build();
        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        return ResponseEntity.ok(vacancyService.list(year));
    }

    @PostMapping("/{loadId}/assign")
    public ResponseEntity<?> assign(@PathVariable Long loadId, @RequestBody Map<String, Object> body, Authentication auth) {
        User user = admin(auth);
        if (user == null) return ResponseEntity.status(403).build();
        Object raw = body.get("teacherId");
        if (raw == null || raw.toString().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Выберите преподавателя"));
        }
        VacancyService.AssignResult result;
        try {
            result = vacancyService.assign(loadId, Long.valueOf(raw.toString()), user.getUsername());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        boolean changed = result.placements().stream().anyMatch(p -> p.pairsPerWeek() > 0 || p.rebuilt());
        if (changed) {
            afterSaveService.submit("Назначение вакансии: " + result.teacherName(), null, result.academicYear(),
                    result.placements().stream().map(VacancyService.Placement::semester).distinct().toList(),
                    result.groupLoadIds());
        }
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("loadId", result.loadId());
        response.put("teacherName", result.teacherName());
        response.put("academicYear", result.academicYear());
        response.put("practice", result.practice());
        response.put("placements", result.placements());
        response.put("afterSave", changed);
        return ResponseEntity.ok(response);
    }

    private User admin(Authentication auth) {
        if (auth == null) return null;
        User user = userRepository.findByEmail(auth.getName()).orElse(null);
        return user != null && user.getRole() == User.Role.ADMIN ? user : null;
    }
}
