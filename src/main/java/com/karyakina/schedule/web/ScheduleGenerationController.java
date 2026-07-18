package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.ScheduleGenerationResultDto;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.ScheduleGeneratorService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * МОДУЛЬ АВТОМАТИЧЕСКОГО СОСТАВЛЕНИЯ РАСПИСАНИЯ. Доступен только администратору.
 * /preview - черновик без сохранения, /apply - сохранить результат в расписание.
 */
@RestController
@RequestMapping("/api/schedule-generation")
@RequiredArgsConstructor
public class ScheduleGenerationController {

    private final ScheduleGeneratorService generatorService;
    private final UserRepository userRepository;

    @PostMapping("/preview")
    public ResponseEntity<ScheduleGenerationResultDto> preview(
            @RequestParam(name = "academicYear", required = false) Integer academicYear,
            Authentication authentication) {
        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();

        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        return ResponseEntity.ok(generatorService.generate(year, false));
    }

    @PostMapping("/apply")
    public ResponseEntity<ScheduleGenerationResultDto> apply(
            @RequestParam(name = "academicYear", required = false) Integer academicYear,
            Authentication authentication) {
        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();

        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        return ResponseEntity.ok(generatorService.generate(year, true));
    }

    private User requireAdmin(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getRole() == User.Role.ADMIN ? user : null;
    }
}
