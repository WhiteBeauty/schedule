package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.GenerationRequestDTO;
import com.karyakina.schedule.dto.GenerationResultDTO;
import com.karyakina.schedule.dto.MissingResourceRequest;
import com.karyakina.schedule.dto.RescheduleResultDTO;
import com.karyakina.schedule.dto.ResolutionDecision;
import com.karyakina.schedule.dto.ScheduleGenerationResultDto;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.ScheduleGeneratorService;
import com.karyakina.schedule.service.SickLeaveReschedulingService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * МОДУЛЬ АВТОМАТИЧЕСКОГО СОСТАВЛЕНИЯ РАСПИСАНИЯ. Доступен только администратору.
 *
 * <p>Интерактивный цикл: {@code /run} — черновик и вопросы, {@code /{sessionId}/resolve} —
 * ответы администратора и пересчёт, {@code /{sessionId}/commit} — фиксация в расписании.
 * Старые {@code /preview} и {@code /apply} оставлены, чтобы существующие кнопки продолжали работать.
 *
 * <p>Любая неожиданная ошибка превращается в {@link GenerationResultDTO} со статусом FAILED
 * и текстом причины — фронтенд никогда не получает пустую ошибку.
 */
@RestController
@RequestMapping("/api/schedule-generation")
@RequiredArgsConstructor
@Slf4j
public class ScheduleGenerationController {

    private final ScheduleGeneratorService generatorService;
    private final SickLeaveReschedulingService sickLeaveService;
    private final UserRepository userRepository;

    // ------------------------------------------------------------------ интерактивный режим

    /** Запуск автосоставления: возвращает черновик + вопросы, которые нужно решить. */
    @PostMapping("/run")
    public ResponseEntity<GenerationResultDTO> run(@RequestBody(required = false) GenerationRequestDTO request,
                                                   Authentication authentication) {
        User admin = requireAdmin(authentication);
        if (admin == null) {
            return forbidden();
        }
        GenerationRequestDTO effective = request != null ? request
                : GenerationRequestDTO.forYear(AcademicYearUtil.getCurrentAcademicYearStart(), false);
        return ResponseEntity.ok(generatorService.generateInteractive(effective, adminDisplayName(admin)));
    }

    /** Ответы администратора на вопросы: применяем и пересобираем расписание. */
    @PostMapping("/{sessionId}/resolve")
    public ResponseEntity<GenerationResultDTO> resolve(@PathVariable String sessionId,
                                                       @RequestBody(required = false) List<ResolutionDecision> decisions,
                                                       Authentication authentication) {
        User admin = requireAdmin(authentication);
        if (admin == null) {
            return forbidden();
        }
        return ResponseEntity.ok(generatorService.applyDecisions(sessionId,
                decisions == null ? List.of() : decisions, adminDisplayName(admin)));
    }

    /** Фиксация черновика в расписании. */
    @PostMapping("/{sessionId}/commit")
    public ResponseEntity<GenerationResultDTO> commit(@PathVariable String sessionId,
                                                      Authentication authentication) {
        User admin = requireAdmin(authentication);
        if (admin == null) {
            return forbidden();
        }
        return ResponseEntity.ok(generatorService.commit(sessionId, adminDisplayName(admin)));
    }

    // ------------------------------------------------------------------ форс-мажор

    /**
     * Преподаватель заболел: ищем замены, переносим пары, остаток отдаём администратору
     * с вариантами действий.
     *
     * <p>Тело запроса: {@code {"teacherId":12,"startDate":"2026-09-07","endDate":"2026-09-09"}}
     */
    @PostMapping("/sick-leave")
    public ResponseEntity<RescheduleResultDTO> sickLeave(@RequestBody Map<String, Object> body,
                                                         Authentication authentication) {
        User admin = requireAdmin(authentication);
        if (admin == null) {
            return ResponseEntity.status(403).build();
        }
        Long teacherId = parseLong(body.get("teacherId"));
        LocalDate start = parseDate(body.get("startDate"));
        LocalDate end = parseDate(body.get("endDate"));
        if (teacherId == null || start == null || end == null) {
            return ResponseEntity.ok(RescheduleResultDTO.failed(teacherId, start, end,
                    MissingResourceRequest.technical(
                            "Укажите преподавателя и период отсутствия в формате ГГГГ-ММ-ДД.")));
        }
        return ResponseEntity.ok(sickLeaveService.handleTeacherSickLeave(teacherId, start, end));
    }

    // ------------------------------------------------------------------ прежние эндпоинты

    @PostMapping("/preview")
    public ResponseEntity<ScheduleGenerationResultDto> preview(
            @RequestParam(name = "academicYear", required = false) Integer academicYear,
            Authentication authentication) {
        User admin = requireAdmin(authentication);
        if (admin == null) {
            return ResponseEntity.status(403).build();
        }
        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        return ResponseEntity.ok(generatorService.generate(year, false, null));
    }

    @PostMapping("/apply")
    public ResponseEntity<ScheduleGenerationResultDto> apply(
            @RequestParam(name = "academicYear", required = false) Integer academicYear,
            Authentication authentication) {
        User admin = requireAdmin(authentication);
        if (admin == null) {
            return ResponseEntity.status(403).build();
        }
        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        return ResponseEntity.ok(generatorService.generate(year, true, adminDisplayName(admin)));
    }

    // ------------------------------------------------------------------ обработка ошибок

    /**
     * Ни одна ошибка не уходит на фронтенд пустым 500-м: администратор видит текст причины
     * в том же формате, что и обычные вопросы генератора.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<GenerationResultDTO> handleAny(Exception e) {
        log.error("Ошибка в модуле автосоставления", e);
        return ResponseEntity.ok(GenerationResultDTO.failed(null, MissingResourceRequest.technical(
                "Запрос не выполнен: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage())
                        + ". Данные не изменены.")));
    }

    // ------------------------------------------------------------------ вспомогательное

    private ResponseEntity<GenerationResultDTO> forbidden() {
        return ResponseEntity.status(403).body(GenerationResultDTO.failed(null,
                MissingResourceRequest.technical("Автосоставление доступно только администратору.")));
    }

    private String adminDisplayName(User user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        if (first != null && !first.isBlank()) {
            return last != null && !last.isBlank() ? first + " " + last : first;
        }
        return user.getUsername();
    }

    private User requireAdmin(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        User user = userRepository.findByEmail(authentication.getName()).orElse(null);
        return user != null && user.getRole() == User.Role.ADMIN ? user : null;
    }

    private Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private LocalDate parseDate(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
