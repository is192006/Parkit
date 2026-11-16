package com.parkit.service;

import com.parkit.domain.entity.User;
import com.parkit.domain.enums.UserRole;
import com.parkit.dto.request.RegisterRequest;
import com.parkit.dto.response.UserResponse;
import com.parkit.exception.DuplicateResourceException;
import com.parkit.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer registration (FR-01, FR-03, FR-04, FR-08). */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a CUSTOMER; the role is never taken from the request (BR-02).
     * A concurrent duplicate is still stopped by the unique index on lower(email) (409).
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email().trim();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateResourceException("An account with this email already exists.");
        }
        User user = new User(request.name().trim(), email, passwordEncoder.encode(request.password()),
                request.phone().trim(), UserRole.CUSTOMER);
        return UserResponse.from(userRepository.saveAndFlush(user));
    }
}
