package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.ImportReportDto;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.ImportService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * МОДУЛЬ ИМПОРТА ДАННЫХ (Excel / CSV). Доступен только администратору.
 */
@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;
    private final UserRepository userRepository;

    @PostMapping("/loads")
    public ResponseEntity<ImportReportDto> importLoads(
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
        ImportReportDto report = importService.importFile(file, year);
        return ResponseEntity.ok(report);
    }
}
