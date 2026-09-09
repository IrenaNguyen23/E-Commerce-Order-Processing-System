package com.commerceflow.authservice.service;

import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.authservice.config.AdminBootstrapProperties;
import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the bootstrap back-office account on first start.
 *
 * <p>The password is hashed here rather than seeded as a literal in a Flyway script, so no usable
 * credential is ever committed to the repository. Disabled unless
 * {@code commerceflow.bootstrap.admin.enabled=true}, and idempotent when it does run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "commerceflow.bootstrap.admin", name = "enabled",
        havingValue = "true")
public class AdminAccountInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminBootstrapProperties properties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = properties.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            log.info("Bootstrap admin {} already exists, nothing to do", email);
            return;
        }
        if (properties.getPassword() == null || properties.getPassword().length() < 12) {
            throw new IllegalStateException(
                    "commerceflow.bootstrap.admin.password must be at least 12 characters");
        }

        User admin = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordEncoder.encode(properties.getPassword()))
                .fullName(properties.getFullName())
                .enabled(true)
                // Created by an operator against an address they chose. Requiring a mailbox
                // round trip here would mean the very first login needs SMTP to already work.
                .emailVerified(true)
                .roles(EnumSet.of(Role.ADMIN, Role.CUSTOMER))
                .build();

        userRepository.save(admin);
        log.warn("Created bootstrap admin account {} — rotate this password immediately", email);
    }
}
