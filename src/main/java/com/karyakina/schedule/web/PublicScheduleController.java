package com.karyakina.schedule.web;

import com.karyakina.schedule.dto.MonthScheduleDto;
import com.karyakina.schedule.repository.StudyGroupRepository;
import com.karyakina.schedule.service.MonthScheduleService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class PublicScheduleController {

    private final StudyGroupRepository groupRepository;
    private final MonthScheduleService monthScheduleService;

    @GetMapping("/student")
    public String studentPage(Model model) {
        model.addAttribute("currentAcademicYear", AcademicYearUtil.getAcademicYearString());
        return "student";
    }

    @GetMapping("/api/public/groups")
    @ResponseBody
    public List<Map<String, Object>> groups() {
        return groupRepository.findAll().stream()
                .sorted(Comparator.comparing(g -> g.getName() == null ? "" : g.getName()))
                .map(g -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", g.getId());
                    row.put("name", g.getName());
                    row.put("course", g.getCourse());
                    row.put("specialty", g.getSpecialty());
                    return row;
                })
                .toList();
    }

    @GetMapping("/api/public/schedule")
    @ResponseBody
    public ResponseEntity<?> schedule(@RequestParam Long groupId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 45) {
            return ResponseEntity.badRequest().body(Map.of("error", "Слишком большой период"));
        }
        MonthScheduleDto data = monthScheduleService.getRange(from, to, null, null, groupId, false);
        List<Map<String, Object>> lessons = data.lessons().stream().map(l -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", l.date());
            row.put("pairNumber", l.pairNumber());
            row.put("startTime", l.startTime());
            row.put("endTime", l.endTime());
            row.put("discipline", l.disciplineName());
            row.put("teacher", l.actualTeacherName());
            row.put("classroom", l.classroom());
            row.put("substituted", l.substituted());
            row.put("movedFrom", l.movedFrom());
            return row;
        }).toList();
        return ResponseEntity.ok(Map.of("lessons", lessons, "events", data.events()));
    }
}
