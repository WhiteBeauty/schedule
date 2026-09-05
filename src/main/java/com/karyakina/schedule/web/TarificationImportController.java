package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.ImportReportDto;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.TarificationImportService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * МОДУЛЬ ИМПОРТА ФАЙЛА "ТАРИФИКАЦИЯ" (годовая сводная таблица нагрузки, лист "Часовка").
 * Отдельный от {@link ImportController} путь, так как формат файла принципиально другой
 * (сводная pivot-таблица, а не построчный список) — см. {@link TarificationImportService}.
 * Доступен только администратору. Групы всегда создаются из файла (см. решение в
 * ImportPersistenceService#applyTarificationRows), поэтому экрана сверки не требуется —
 * импорт применяется одним шагом.
 */
@RestController
@RequestMapping("/api/import/tarification")
@RequiredArgsConstructor
public class TarificationImportController {

    private final TarificationImportService tarificationImportService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<ImportReportDto> importTarification(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "academicYear", required = false) Integer academicYear,
            Authentication authentication) {

        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        ImportReportDto report = tarificationImportService.importFile(file, year);
        return ResponseEntity.ok(report);
    }
}
