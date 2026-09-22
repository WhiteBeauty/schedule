package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.Tarification;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.ImportReportDto;
import com.karyakina.schedule.repository.TarificationRepository;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.TarificationImportService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TarificationImportController {

    private final TarificationImportService tarificationImportService;
    private final TarificationRepository tarificationRepository;
    private final UserRepository userRepository;

    @PostMapping("/api/import/tarification")
    public ResponseEntity<ImportReportDto> importTarification(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "academicYear", required = false) Integer academicYear,
            @RequestParam(name = "name", required = false) String name,
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
        ImportReportDto report = tarificationImportService.importFile(file, year, name, adminDisplayName(user));
        return ResponseEntity.ok(report);
    }

    @GetMapping("/api/tarifications")
    public ResponseEntity<List<Tarification>> list(
            @RequestParam(name = "academicYear", required = false) Integer academicYear) {
        List<Tarification> tarifications = academicYear != null
                ? tarificationRepository.findByAcademicYearOrderByImportedAtDesc(academicYear)
                : tarificationRepository.findAllByOrderByImportedAtDesc();
        return ResponseEntity.ok(tarifications);
    }

    private String adminDisplayName(User user) {
        return user.getFirstName() != null ? user.getFirstName() + " " + user.getLastName() : user.getEmail();
    }
}
