package com.karyakina.schedule.web;

import com.karyakina.schedule.domain.Curatorship;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.UserRepository;
import com.karyakina.schedule.service.CuratorshipService;
import com.karyakina.schedule.util.AcademicYearUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/curatorships")
@RequiredArgsConstructor
public class CuratorshipController {

    private final CuratorshipService service;
    private final UserRepository userRepository;

    @GetMapping
    public List<Curatorship> list(Authentication auth) {
        User user = user(auth);
        if (user.getRole() == User.Role.ADMIN) return service.findAll();
        return user.getTeacher() == null ? List.of() : service.findByTeacherId(user.getTeacher().getId());
    }

    @GetMapping("/my")
    public List<Curatorship> my(Authentication auth) {
        return list(auth);
    }

    @GetMapping("/teacher/{teacherId}")
    public ResponseEntity<List<Curatorship>> byTeacher(@PathVariable Long teacherId, Authentication auth) {
        User user = user(auth);
        if (user.getRole() != User.Role.ADMIN
                && (user.getTeacher() == null || !user.getTeacher().getId().equals(teacherId))) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(service.findByTeacherId(teacherId));
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(@RequestParam(required = false) Integer year, Authentication auth) {
        if (user(auth).getRole() != User.Role.ADMIN) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(service.overview(year != null ? year : AcademicYearUtil.getCurrentAcademicYearStart()));
    }

    @GetMapping("/calendar")
    public Map<String, Object> calendar(@RequestParam(required = false) Integer year) {
        int y = year != null ? year : AcademicYearUtil.getCurrentAcademicYearStart();
        return Map.of("weeks", Math.round(CuratorshipService.yearWeeks(y) * 10) / 10.0,
                "months", CuratorshipService.monthWeeks(y));
    }

    @GetMapping("/by-id/{id}")
    public ResponseEntity<Curatorship> byId(@PathVariable Long id, Authentication auth) {
        Curatorship c = service.findById(id).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();
        if (!canEdit(user(auth), c)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(c);
    }

    @GetMapping("/{id}/group-info")
    @Transactional(readOnly = true)
    public ResponseEntity<?> groupInfo(@PathVariable Long id, Authentication auth) {
        Curatorship c = service.findById(id).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();
        if (!canEdit(user(auth), c)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(service.groupInfo(c));
    }

    @PostMapping
    public ResponseEntity<?> assign(@RequestBody Map<String, Object> body, Authentication auth) {
        if (user(auth).getRole() != User.Role.ADMIN) return ResponseEntity.status(403).build();
        return handle(() -> service.assign(asLong(body.get("teacherId")), asLong(body.get("groupId")),
                asInt(body.get("hours"))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (user(auth).getRole() != User.Role.ADMIN) return ResponseEntity.status(403).build();
        return handle(() -> service.update(id, asLong(body.get("teacherId")), asInt(body.get("hours"))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        if (user(auth).getRole() != User.Role.ADMIN) return ResponseEntity.status(403).build();
        service.delete(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/event")
    public ResponseEntity<?> addEvent(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        return saveEvent(id, null, body, auth);
    }

    @PutMapping("/{id}/event")
    public ResponseEntity<?> updateEvent(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        return saveEvent(id, asInt(body.get("eventIdx")), body, auth);
    }

    @DeleteMapping("/{id}/event/{index}")
    public ResponseEntity<?> deleteEvent(@PathVariable Long id, @PathVariable int index, Authentication auth) {
        if (!allowed(id, auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(service.deleteEvent(id, index));
    }

    @PostMapping("/{id}/log")
    public ResponseEntity<?> addLog(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!allowed(id, auth)) return ResponseEntity.status(403).build();
        return handle(() -> service.saveLog(id, null, str(body.get("logEntry"))));
    }

    @PutMapping("/{id}/log")
    public ResponseEntity<?> updateLog(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
        if (!allowed(id, auth)) return ResponseEntity.status(403).build();
        return handle(() -> service.saveLog(id, asInt(body.get("logIdx")), str(body.get("logEntry"))));
    }

    @DeleteMapping("/{id}/log/{index}")
    public ResponseEntity<?> deleteLog(@PathVariable Long id, @PathVariable int index, Authentication auth) {
        if (!allowed(id, auth)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(service.deleteLog(id, index));
    }

    private ResponseEntity<?> saveEvent(Long id, Integer index, Map<String, Object> body, Authentication auth) {
        if (!allowed(id, auth)) return ResponseEntity.status(403).build();
        return handle(() -> {
            String date = str(body.get("date"));
            String hours = str(body.get("hours"));
            return service.saveEvent(id, index,
                    date == null || date.isBlank() ? null : LocalDate.parse(date),
                    hours == null || hours.isBlank() ? 0.0 : Double.valueOf(hours.replace(',', '.')),
                    str(body.get("type")), str(body.get("description")));
        });
    }

    private ResponseEntity<?> handle(java.util.function.Supplier<Object> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean allowed(Long id, Authentication auth) {
        Curatorship c = service.findById(id).orElse(null);
        return c != null && canEdit(user(auth), c);
    }

    private static boolean canEdit(User user, Curatorship c) {
        return user.getRole() == User.Role.ADMIN
                || (user.getTeacher() != null && c.getTeacher().getId().equals(user.getTeacher().getId()));
    }

    private User user(Authentication auth) {
        return userRepository.findByEmail(auth.getName()).orElseThrow(() -> new RuntimeException("User not found"));
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v) {
        return v == null || v.toString().isBlank() ? null : Long.valueOf(v.toString());
    }

    private static Integer asInt(Object v) {
        return v == null || v.toString().isBlank() ? null : Integer.valueOf(v.toString());
    }
}
