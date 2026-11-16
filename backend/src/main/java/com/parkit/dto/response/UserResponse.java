package com.parkit.dto.response;

import com.parkit.domain.entity.User;
import com.parkit.domain.enums.UserRole;
import java.time.Instant;

/** User profile. Never contains the password hash. */
public record UserResponse(Long id, String name, String email, String phone, UserRole role,
                           boolean active, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.isActive(), user.getCreatedAt());
    }
}
