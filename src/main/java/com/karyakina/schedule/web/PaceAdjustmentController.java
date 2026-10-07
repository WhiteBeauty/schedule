package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.PaceAdjustmentSuggestion;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.SchedulePaceService;
import com.karyakina.schedule.service.SettingsService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pace-adjustments")
@RequiredArgsConstructor
public class PaceAdjustmentController {

    private final SchedulePaceService schedulePaceService;
    private final SettingsService settingsService;
    private final UserRepository userRepository;

    @PostMapping("/check")
    public ResponseEntity<?> check(
            @RequestParam(required = false) Integer academicYear,
            @RequestParam(required = false) Integer semester,
            Authentication authentication) {
        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();

        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        int sem = semester != null ? semester : AcademicYearUtil.getCurrentSemester();
        List<PaceAdjustmentSuggestion> created = schedulePaceService.checkPace(year, sem);
        return ResponseEntity.ok(Map.of("created", created.size()));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable Long id, Authentication authentication) {
        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(schedulePaceService.accept(id));
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<?> decline(@PathVariable Long id, Authentication authentication) {
        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(schedulePaceService.decline(id));
    }

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings() {
        return ResponseEntity.ok(Map.of("enabled", settingsService.isPaceRecalculationEnabled()));
    }

    @PutMapping("/settings")
    public ResponseEntity<?> updateSettings(@RequestBody Map<String, Object> body, Authentication authentication) {
        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        settingsService.setPaceRecalculationEnabled(enabled);
        return ResponseEntity.ok(Map.of("enabled", enabled));
    }

    private User requireAdmin(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        return (user != null && user.getRole() == User.Role.ADMIN) ? user : null;
    }
}
