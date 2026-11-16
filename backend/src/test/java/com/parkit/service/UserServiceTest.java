package com.parkit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkit.domain.entity.User;
import com.parkit.domain.enums.UserRole;
import com.parkit.dto.request.ChangePasswordRequest;
import com.parkit.dto.request.RegisterRequest;
import com.parkit.exception.DuplicateResourceException;
import com.parkit.exception.InvalidCurrentPasswordException;
import com.parkit.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests for registration and password change (no database). */
class UserServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AuthService authService = new AuthService(users, encoder);
    private final UserService userService = new UserService(users, encoder);

    @Test
    void registerStoresBcryptHashAndCustomerRole() {
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.register(new RegisterRequest(" Asha ", " Asha@Example.com ", "Secret123", "9876543210"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        User u = saved.getValue();
        assertThat(u.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(u.getName()).isEqualTo("Asha");
        assertThat(u.getEmail()).isEqualTo("Asha@Example.com");
        assertThat(u.getPasswordHash()).doesNotContain("Secret123").startsWith("$2");
        assertThat(encoder.matches("Secret123", u.getPasswordHash())).isTrue();
    }

    @Test
    void registerRejectsExistingEmailIgnoringCase() {
        when(users.existsByEmailIgnoreCase("dup@example.com")).thenReturn(true);
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("A", "dup@example.com", "Secret123", "9876543210")))
                .isInstanceOf(DuplicateResourceException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void changePasswordRequiresCorrectCurrentPassword() {
        User u = new User("A", "a@example.com", encoder.encode("OldPass123"), "9876543210", UserRole.CUSTOMER);
        when(users.findById(1L)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> userService.changePassword(1L, new ChangePasswordRequest("wrong", "NewPass123")))
                .isInstanceOf(InvalidCurrentPasswordException.class);
        assertThat(encoder.matches("OldPass123", u.getPasswordHash())).isTrue();

        userService.changePassword(1L, new ChangePasswordRequest("OldPass123", "NewPass123"));
        assertThat(encoder.matches("NewPass123", u.getPasswordHash())).isTrue();
        assertThat(u.getPasswordHash()).doesNotContain("NewPass123");
    }
}
