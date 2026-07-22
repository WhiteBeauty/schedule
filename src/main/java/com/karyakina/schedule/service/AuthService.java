package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.Teacher;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.dto.RegisterRequest;
import com.karyakina.schedule.repository.TeacherRepository;
import com.karyakina.schedule.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User register(RegisterRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new RuntimeException("Пароли не совпадают");
        }

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Логин уже занят");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email уже зарегистрирован");
        }

        // БЕЗОПАСНОСТЬ: роль из тела запроса ИГНОРИРУЕТСЯ намеренно — самостоятельная
        // регистрация создаёт только TEACHER. Раньше request.getRole() бралась как есть,
        // что позволяло получить ADMIN простым изменением поля в запросе. Администратор
        // назначается только другим администратором через /admin/users (см. UserAdminService).
        Teacher teacher = teacherRepository.save(Teacher.builder()
                .fullName(request.getLastName() + " " + request.getFirstName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .birthDate(request.getBirthDate())
                .department("Кафедра")
                .position("Преподаватель")
                .rate(1.0)
                .build());

        String encodedPassword = passwordEncoder.encode(request.getPassword());
        log.info("Registering user: {} with encoded password length: {}", request.getEmail(), encodedPassword.length());

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(encodedPassword)
                .role(User.Role.TEACHER)
                .teacher(teacher)
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .birthDate(request.getBirthDate())
                .build();

        return userRepository.save(user);
    }
}
