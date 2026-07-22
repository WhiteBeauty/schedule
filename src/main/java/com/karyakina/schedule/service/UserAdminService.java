package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.AuditLog;
import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.AuditLogRepository;
import com.karyakina.schedule.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * "Администратор не создаётся сам — администратор назначается". Единственный способ
 * получить права ADMIN — чтобы их выдал уже действующий администратор через этот сервис.
 * Каждое повышение/понижение фиксируется в AuditLog: кто, кого и когда.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAdminService {

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public List<AuditLog> findAuditLog() {
        return auditLogRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public User promoteToAdmin(Long userId, User actor) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден: " + userId));

        if (target.getRole() == User.Role.ADMIN) {
            return target; // уже администратор, ничего не делаем
        }

        target.setRole(User.Role.ADMIN);
        User saved = userRepository.save(target);

        auditLogRepository.save(AuditLog.builder()
                .action("PROMOTE_TO_ADMIN")
                .actorUsername(actor.getUsername())
                .targetUsername(target.getUsername())
                .details("Назначен администратором")
                .build());

        return saved;
    }

    @Transactional
    public User demoteFromAdmin(Long userId, User actor) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден: " + userId));

        if (target.getRole() != User.Role.ADMIN) {
            return target; // уже не администратор
        }

        if (target.getId().equals(actor.getId())) {
            throw new RuntimeException("Нельзя снять права администратора с самого себя");
        }

        long adminCount = userRepository.findAll().stream()
                .filter(u -> u.getRole() == User.Role.ADMIN)
                .count();
        if (adminCount <= 1) {
            throw new RuntimeException("Нельзя снять права с единственного администратора системы");
        }

        target.setRole(User.Role.TEACHER);
        User saved = userRepository.save(target);

        auditLogRepository.save(AuditLog.builder()
                .action("DEMOTE_FROM_ADMIN")
                .actorUsername(actor.getUsername())
                .targetUsername(target.getUsername())
                .details("Права администратора сняты")
                .build());

        return saved;
    }
}
