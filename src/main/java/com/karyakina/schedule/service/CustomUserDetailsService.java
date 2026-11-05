package com.karyakina.schedule.service;

import com.karyakina.schedule.domain.User;
import com.karyakina.schedule.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        log.info("Attempting to load user by: {}", usernameOrEmail);
        String login = usernameOrEmail == null ? "" : usernameOrEmail.trim();
        // логины преподавателей строчными а с телефона часто пишут с большой буквы
        User user = userRepository.findByEmail(login)
                .or(() -> userRepository.findByUsername(login))
                .or(() -> userRepository.findByUsername(login.toLowerCase(java.util.Locale.ROOT)))
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Пользователь не найден: " + usernameOrEmail));

        log.info("User found: {} with email: {}", user.getUsername(), user.getEmail());
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }
}
