package com.parkit.security;

import com.parkit.domain.entity.User;
import com.parkit.domain.enums.UserRole;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Security principal stored in the HTTP session. Controllers obtain the acting
 * user's id from here, never from the client (ARCHITECTURE.md §3).
 */
public final class AuthenticatedUser implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final String email;
    private final transient String passwordHash;
    private final UserRole role;
    private final boolean active;

    public AuthenticatedUser(Long userId, String email, String passwordHash, UserRole role, boolean active) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = active;
    }

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getPasswordHash(),
                user.getRole(), user.isActive());
    }

    public Long getUserId() {
        return userId;
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    /** Deactivated users cannot log in. */
    @Override
    public boolean isEnabled() {
        return active;
    }
}
