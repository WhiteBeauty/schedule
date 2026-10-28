package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.ImportPreviewDto;
import com.karyakina.schedule.dto.ImportReportDto;
import com.karyakina.schedule.dto.ImportRowDecisionDto;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.ImportService;
import com.karyakina.schedule.util.AcademicYearUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@lombok.extern.slf4j.Slf4j
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;
    private final com.karyakina.schedule.service.TarificationImportService tarificationImportService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/loads")
    public ResponseEntity<ImportReportDto> importLoads(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "academicYear", required = false) Integer academicYear,
            Authentication authentication) {

        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        ImportReportDto report = importService.importFile(file, year);
        return ResponseEntity.ok(report);
    }

    @PostMapping("/preview")
    public ResponseEntity<ImportPreviewDto> preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "academicYear", required = false) Integer academicYear,
            Authentication authentication) {

        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();
        ImportPreviewDto preview = importService.previewImport(file, year);
        if (!preview.isSuccess()) {
            try {
                var tarif = tarificationImportService.parse(file);
                if (tarif.fatalError == null && !tarif.rows.isEmpty()) {
                    log.info("Импорт нагрузки: файл {} похож на тарификацию ({} строк) — подсказываем вкладку «Тарификация»",
                            file.getOriginalFilename(), tarif.rows.size());
                    preview.setSummary("Это файл тарификации (лист «" + tarif.sheetName + "», " + tarif.rows.size()
                            + " записей), а не файл нагрузки. Загрузите его во вкладке «Тарификация».");
                    preview.setLooksLikeTarification(true);
                }
            } catch (Exception e) {
                log.debug("Проверка файла как тарификации не удалась: {}", e.getMessage());
            }
            log.warn("Импорт нагрузки: предпросмотр файла {} не удался: {}", file.getOriginalFilename(), preview.getSummary());
        } else {
            log.info("Импорт нагрузки: предпросмотр файла {} — {}", file.getOriginalFilename(), preview.getSummary());
        }
        return ResponseEntity.ok(preview);
    }

    @PostMapping("/confirm")
    public ResponseEntity<ImportReportDto> confirm(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "academicYear", required = false) Integer academicYear,
            @RequestParam(name = "decisions", required = false) String decisionsJson,
            Authentication authentication) {

        User user = requireAdmin(authentication);
        if (user == null) return ResponseEntity.status(403).build();
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        int year = academicYear != null ? academicYear : AcademicYearUtil.getCurrentAcademicYearStart();

        Map<Integer, ImportRowDecisionDto> decisions;
        try {
            if (decisionsJson == null || decisionsJson.isBlank()) {
                decisions = Map.of();
            } else {
                List<ImportRowDecisionDto> list = objectMapper.readValue(decisionsJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ImportRowDecisionDto.class));
                decisions = list.stream().collect(Collectors.toMap(ImportRowDecisionDto::getRowIndex, d -> d));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        ImportReportDto report = importService.confirmImport(file, year, decisions);
        return ResponseEntity.ok(report);
    }

    private User requireAdmin(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getRole() == User.Role.ADMIN ? user : null;
    }
}
