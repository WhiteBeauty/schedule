package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.HoursRecalculationService;
import com.karyakina.schedule.service.ScheduleService;
import com.karyakina.schedule.service.SettingsService;
import com.karyakina.schedule.service.SpecialEventService;
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
    private final SpecialEventService specialEventService;
    private final ScheduleService scheduleService;
    private final HoursRecalculationService hoursRecalculationService;
    private final SettingsService settingsService;
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
        if (result.placements().stream().anyMatch(p -> p.pairsPerWeek() > 0 || p.rebuilt())) {
            afterPlacement(result);
        }
        return ResponseEntity.ok(result);
    }

    private void afterPlacement(VacancyService.AssignResult result) {
        int year = result.academicYear();
        try {
            specialEventService.reapplyEvents(year);
        } catch (Exception e) {
            log.error("Не удалось снять пары с дней экзаменов и практик", e);
        }
        try {
            scheduleService.autoDeductHours(AcademicYearUtil.getCurrentAcademicYearStart());
        } catch (Exception e) {
            log.error("Не удалось засчитать прошедшие пары после назначения вакансии", e);
        }
        if (!settingsService.isPaceRecalculationEnabled()) return;
        for (VacancyService.Placement placement : result.placements()) {
            try {
                hoursRecalculationService.recalculate(year, placement.semester(), result.groupLoadIds(), false);
            } catch (Exception e) {
                log.error("Пересчёт по часам после назначения вакансии не выполнен", e);
            }
        }
    }

    private User admin(Authentication auth) {
        if (auth == null) return null;
        User user = userRepository.findByEmail(auth.getName()).orElse(null);
        return user != null && user.getRole() == User.Role.ADMIN ? user : null;
    }
}
