package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.Tarification;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.ImportReportDto;
import com.karyakina.schedule.repository.TarificationRepository;
import com.karyakina.schedule.repository.TeacherLoadRepository;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.TarificationImportService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class TarificationImportController {

    private final TarificationImportService tarificationImportService;
    private final TarificationRepository tarificationRepository;
    private final TeacherLoadRepository teacherLoadRepository;
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
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(name = "academicYear", required = false) Integer academicYear) {
        List<Tarification> tarifications = academicYear != null
                ? tarificationRepository.findByAcademicYearOrderByImportedAtDesc(academicYear)
                : tarificationRepository.findAllByOrderByImportedAtDesc();
        List<Map<String, Object>> result = tarifications.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("academicYear", t.getAcademicYear());
            m.put("sourceFileName", t.getSourceFileName());
            m.put("importedBy", t.getImportedBy());
            m.put("importedAt", t.getImportedAt());
            m.put("loadCount", teacherLoadRepository.countByTarificationId(t.getId()));
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/api/tarifications/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (user.getRole() != User.Role.ADMIN) {
            return ResponseEntity.status(403).build();
        }
        Tarification tarification = tarificationRepository.findById(id).orElse(null);
        if (tarification == null) {
            return ResponseEntity.notFound().build();
        }
        long loadCount = teacherLoadRepository.countByTarificationId(id);
        if (loadCount > 0) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Нельзя удалить: с тарификацией связано записей нагрузки — " + loadCount
                            + ". Сначала удалите или перенесите эту нагрузку."));
        }
        tarificationRepository.delete(tarification);
        return ResponseEntity.ok().build();
    }

    private String adminDisplayName(User user) {
        return user.getFirstName() != null ? user.getFirstName() + " " + user.getLastName() : user.getEmail();
    }
}
