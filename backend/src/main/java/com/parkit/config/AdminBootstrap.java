package com.parkit.config;

import com.parkit.domain.entity.User;
import com.parkit.domain.enums.UserRole;
import com.parkit.dto.request.ValidationPatterns;
import com.parkit.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions the first ADMIN at start-up from secured deployment configuration
 * (FR-04, BR-02, DATABASE_DESIGN.md §10): environment variables
 * {@code PARKIT_ADMIN_EMAIL}, {@code PARKIT_ADMIN_PASSWORD},
 * {@code PARKIT_ADMIN_PHONE} and optionally {@code PARKIT_ADMIN_NAME}.
 *
 * <p>Runs only when no ADMIN exists. Never promotes an existing account,
 * never logs the password, and stores only its BCrypt hash.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /** Result of a bootstrap attempt (useful for logging and tests). */
    public enum Outcome { CREATED, ADMIN_EXISTS, NOT_CONFIGURED, EMAIL_IN_USE, INVALID_CONFIGURATION }

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties properties;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          AdminProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    @Transactional
    public Outcome bootstrap() {
        if (userRepository.existsByRole(UserRole.ADMIN)) {
            return Outcome.ADMIN_EXISTS;
        }
        if (!properties.isConfigured()) {
            log.warn("No ADMIN account exists and PARKIT_ADMIN_EMAIL / PARKIT_ADMIN_PASSWORD / "
                    + "PARKIT_ADMIN_PHONE are not set; admin functions are unavailable.");
            return Outcome.NOT_CONFIGURED;
        }
        String email = properties.email().trim();
        if (!ValidationPatterns.isValidPassword(properties.password())
                || !properties.phone().trim().matches(ValidationPatterns.PHONE)
                || !email.contains("@")) {
            log.error("Admin bootstrap configuration is invalid (email, phone or password rules); "
                    + "no admin was created.");
            return Outcome.INVALID_CONFIGURATION;
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            log.error("Admin bootstrap email is already used by another account; it was not promoted.");
            return Outcome.EMAIL_IN_USE;
        }
        String name = properties.name() == null || properties.name().isBlank()
                ? "Administrator" : properties.name().trim();
        userRepository.save(new User(name, email, passwordEncoder.encode(properties.password()),
                properties.phone().trim(), UserRole.ADMIN));
        log.info("Initial ADMIN account created from deployment configuration.");
        return Outcome.CREATED;
    }
}
