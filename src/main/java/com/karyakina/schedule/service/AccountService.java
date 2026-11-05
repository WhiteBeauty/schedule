package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.AuditLog;
import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.AuditLogRepository;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private static final int MIN_PASSWORD = 6;
    private static final int MAX_PASSWORD = 72;
    private static final String PASSWORD_LETTERS = "abcdefghjkmnpqrstuvwxyz";
    private static final String PASSWORD_DIGITS = "23456789";

    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public record Credentials(Long userId, String name, String login, String password) {
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUsers() {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing((User u) -> u.getRole() == User.Role.ADMIN ? 0 : 1)
                        .thenComparing(AccountService::displayName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", u.getId());
                    row.put("name", displayName(u));
                    row.put("login", u.getUsername());
                    row.put("email", isEmail(u.getEmail()) ? u.getEmail() : null);
                    row.put("role", u.getRole().name());
                    row.put("teacherName", u.getTeacher() != null ? u.getTeacher().getFullName() : null);
                    row.put("mustChangePassword", Boolean.TRUE.equals(u.getMustChangePassword()));
                    return row;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> teachersWithoutLogin() {
        Set<Long> linked = linkedTeacherIds();
        return teacherRepository.findAll().stream()
                .filter(t -> !linked.contains(t.getId()))
                .sorted(Comparator.comparing(Teacher::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(t -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("teacherId", t.getId());
                    row.put("fullName", t.getFullName());
                    row.put("login", baseLogin(t.getFullName()));
                    return row;
                })
                .toList();
    }

    // преподавателям из тарификации входа нет поэтому создаём его сами а пароль показываем один раз
    @Transactional
    public List<Credentials> createTeacherLogins(Collection<Long> teacherIds, User actor) {
        Set<Long> linked = linkedTeacherIds();
        List<Teacher> teachers = (teacherIds == null || teacherIds.isEmpty()
                ? teacherRepository.findAll() : teacherRepository.findAllById(teacherIds)).stream()
                .filter(t -> !linked.contains(t.getId()))
                .sorted(Comparator.comparing(Teacher::getFullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<Credentials> created = new ArrayList<>();
        for (Teacher teacher : teachers) {
            String login = uniqueLogin(baseLogin(teacher.getFullName()));
            String password = generatePassword();
            String email = isEmail(teacher.getEmail()) && !userRepository.existsByEmail(teacher.getEmail())
                    ? teacher.getEmail() : login;
            String[] name = teacher.getFullName().trim().split("\\s+", 2);
            User user = userRepository.save(User.builder()
                    .username(login)
                    .email(email)
                    .password(passwordEncoder.encode(password))
                    .role(User.Role.TEACHER)
                    .teacher(teacher)
                    .lastName(name[0])
                    .firstName(name.length > 1 ? name[1] : null)
                    .phone(teacher.getPhone())
                    .birthDate(teacher.getBirthDate())
                    .mustChangePassword(true)
                    .build());
            audit("CREATE_LOGIN", actor, login, "Вход для " + teacher.getFullName());
            created.add(new Credentials(user.getId(), teacher.getFullName(), login, password));
        }
        log.info("Создан вход {} преподавателям, администратор {}", created.size(), actor.getUsername());
        return created;
    }

    // пустой пароль значит придумать самим и показать администратору
    @Transactional
    public Credentials setPassword(Long userId, String newPassword, User actor) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        boolean generated = newPassword == null || newPassword.isEmpty();
        String password = generated ? generatePassword() : newPassword;
        validate(password);
        target.setPassword(passwordEncoder.encode(password));
        target.setMustChangePassword(!target.getId().equals(actor.getId()));
        userRepository.save(target);
        audit("SET_PASSWORD", actor, target.getUsername(), "Пароль сменён администратором");
        return new Credentials(target.getId(), displayName(target), target.getUsername(), generated ? password : null);
    }

    @Transactional
    public void changeOwnPassword(User user, String currentPassword, String newPassword, String repeatPassword) {
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Текущий пароль указан неверно");
        }
        if (newPassword == null || !newPassword.equals(repeatPassword)) {
            throw new IllegalArgumentException("Новые пароли не совпадают");
        }
        validate(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new IllegalArgumentException("Новый пароль совпадает со старым");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
        audit("CHANGE_OWN_PASSWORD", user, user.getUsername(), "Пароль сменён самим пользователем");
    }

    public static String displayName(User u) {
        if (u.getTeacher() != null) return u.getTeacher().getFullName();
        String last = u.getLastName() != null ? u.getLastName() : "";
        String first = u.getFirstName() != null ? u.getFirstName() : "";
        String name = (last + " " + first).trim();
        return name.isEmpty() ? u.getUsername() : name;
    }

    // логин из фамилии и инициалов латиницей
    static String baseLogin(String fullName) {
        String[] parts = (fullName == null ? "" : fullName).trim().split("[\\s.]+");
        String surname = parts.length > 0 ? translit(parts[0]) : "";
        StringBuilder initials = new StringBuilder();
        for (int i = 1; i < parts.length && i <= 2; i++) {
            if (!parts[i].isEmpty()) initials.append(translit(parts[i].substring(0, 1)));
        }
        if (surname.isEmpty()) surname = "teacher";
        return initials.length() > 0 ? surname + "." + initials : surname;
    }

    private static String translit(String text) {
        String[] map = {"a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s",
                "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"};
        StringBuilder out = new StringBuilder();
        for (char c : text.toLowerCase(Locale.ROOT).replace('ё', 'е').toCharArray()) {
            if (c >= 'а' && c <= 'я') {
                out.append(map[c - 'а']);
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String uniqueLogin(String base) {
        String login = base;
        for (int i = 2; userRepository.existsByUsername(login) || userRepository.existsByEmail(login); i++) {
            login = base + i;
        }
        return login;
    }

    private String generatePassword() {
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            String pool = i == 3 || i == 6 ? PASSWORD_DIGITS : PASSWORD_LETTERS;
            password.append(pool.charAt(random.nextInt(pool.length())));
        }
        return password.toString();
    }

    private static void validate(String password) {
        if (password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new IllegalArgumentException("Пароль должен быть от " + MIN_PASSWORD + " до " + MAX_PASSWORD + " символов");
        }
    }

    private static boolean isEmail(String value) {
        return value != null && value.contains("@");
    }

    private Set<Long> linkedTeacherIds() {
        Set<Long> ids = new HashSet<>();
        userRepository.findAll().forEach(u -> {
            if (u.getTeacher() != null) ids.add(u.getTeacher().getId());
        });
        return ids;
    }

    private void audit(String action, User actor, String target, String details) {
        auditLogRepository.save(AuditLog.builder()
                .action(action)
                .actorUsername(actor.getUsername())
                .targetUsername(target)
                .details(details)
                .build());
    }
}
