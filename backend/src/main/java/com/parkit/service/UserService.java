package com.parkit.service;

import com.parkit.domain.entity.User;
import com.parkit.domain.enums.UserRole;
import com.parkit.dto.request.ChangePasswordRequest;
import com.parkit.dto.request.UpdateProfileRequest;
import com.parkit.dto.response.PageResponse;
import com.parkit.dto.response.UserResponse;
import com.parkit.exception.InvalidCurrentPasswordException;
import com.parkit.exception.ResourceNotFoundException;
import com.parkit.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile, password change and admin customer search (FR-05, SRS §2.2). */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(Long userId) {
        return UserResponse.from(load(userId));
    }

    /** Name and phone only; email is the login identifier and cannot change (FR-05). */
    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = load(userId);
        user.setName(request.name().trim());
        user.setPhone(request.phone().trim());
        return UserResponse.from(userRepository.saveAndFlush(user));
    }

    /** Requires the current password; the new one is stored only as a BCrypt hash. */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = load(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidCurrentPasswordException();
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> searchCustomers(String query, Pageable pageable) {
        Page<User> page = (query == null || query.isBlank())
                ? userRepository.findByRole(UserRole.CUSTOMER, pageable)
                : userRepository.searchByRole(UserRole.CUSTOMER, query.trim(), pageable);
        return PageResponse.from(page, UserResponse::from);
    }

    private User load(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }
}
