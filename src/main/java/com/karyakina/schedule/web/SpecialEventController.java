package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.SpecialEvent;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.SpecialEventDtos;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.SpecialEventService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Экзамен, учебная/производственная практика, вождение (ТЗ п.4-9) — назначение с
 * автопереносом конфликтующих пар. Только для администратора. См. {@link SpecialEventService}.
 */
@RestController
@RequestMapping("/api/special-events")
@RequiredArgsConstructor
public class SpecialEventController {

    private final SpecialEventService specialEventService;
    private final UserRepository userRepository;

    public record AssignRequest(
            String type, // EXAM | PRODUCTION_PRACTICE | STUDY_PRACTICE | DRIVING
            Long groupId,
            Long disciplineId,
            Long teacherLoadId,
            LocalDate startDate,
            LocalDate endDate,
            Integer academicYear
    ) {
    }

    public record ResolveRequest(
            Long teacherLoadId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer pairIndex,
            String classroom
    ) {
    }

    @PostMapping
    public ResponseEntity<?> assign(@RequestBody AssignRequest request, Authentication authentication) {
        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();

        try {
            SpecialEvent.Type type = SpecialEvent.Type.valueOf(request.type());
            // Экзамен — всегда один день. Даже если с фронтенда по ошибке придёт другая
            // endDate (см. баг с незачищенным полем даты окончания в модалке), здесь это
            // перестраховано: для EXAM конец периода всегда равен началу.
            LocalDate end = type == SpecialEvent.Type.EXAM ? request.startDate()
                    : (request.endDate() != null ? request.endDate() : request.startDate());
            Integer year = request.academicYear() != null
                    ? request.academicYear() : AcademicYearUtil.getCurrentAcademicYearStart();
            SpecialEventDtos.Result result = specialEventService.assignEvent(type, request.groupId(),
                    request.disciplineId(), request.teacherLoadId(), request.startDate(), end, year,
                    adminDisplayName(user));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", "Не удалось назначить: " + e.getMessage()));
        }
    }

    @PostMapping("/{eventId}/resolve")
    public ResponseEntity<?> resolve(@PathVariable Long eventId, @RequestBody ResolveRequest request,
                                     Authentication authentication) {
        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();

        try {
            return ResponseEntity.ok(specialEventService.resolveManually(eventId, request.teacherLoadId(),
                    request.fromDate(), request.toDate(), request.pairIndex(), request.classroom()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", "Не удалось перенести: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<SpecialEvent>> list(
            @RequestParam Long groupId,
            @RequestParam(required = false) Integer academicYear) {
        Integer year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        return ResponseEntity.ok(specialEventService.findForGroup(groupId, year));
    }

    @DeleteMapping("/{eventId}")
    public ResponseEntity<?> delete(@PathVariable Long eventId, Authentication authentication) {
        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();

        try {
            specialEventService.deleteEvent(eventId);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", "Не удалось удалить: " + e.getMessage()));
        }
    }

    private User requireAdmin(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        if (user == null || user.getRole() != User.Role.ADMIN) return null;
        return user;
    }

    private String adminDisplayName(User user) {
        return user.getFirstName() != null ? user.getFirstName() + " " + user.getLastName() : user.getEmail();
    }
}
