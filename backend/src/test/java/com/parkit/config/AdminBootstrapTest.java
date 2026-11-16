package com.parkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkit.config.AdminBootstrap.Outcome;
import com.parkit.domain.entity.User;
import com.parkit.domain.enums.UserRole;
import com.parkit.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests for first-admin provisioning (FR-04, BR-02). */
class AdminBootstrapTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private AdminBootstrap bootstrap(String email, String password, String phone) {
        return new AdminBootstrap(users, encoder, new AdminProperties(email, password, null, phone));
    }

    @Test
    void createsAdminWithHashedPasswordWhenNoneExists() {
        when(users.existsByRole(UserRole.ADMIN)).thenReturn(false);
        when(users.existsByEmailIgnoreCase("root@parkit.test")).thenReturn(false);

        Outcome outcome = bootstrap(" root@parkit.test ", "Str0ngPass!", "9000000000").bootstrap();

        assertThat(outcome).isEqualTo(Outcome.CREATED);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.getEmail()).isEqualTo("root@parkit.test");
        assertThat(admin.getName()).isEqualTo("Administrator");
        assertThat(admin.getPasswordHash()).isNotEqualTo("Str0ngPass!").startsWith("$2");
        assertThat(encoder.matches("Str0ngPass!", admin.getPasswordHash())).isTrue();
    }

    @Test
    void skipsWhenAnAdminAlreadyExists() {
        when(users.existsByRole(UserRole.ADMIN)).thenReturn(true);
        assertThat(bootstrap("root@parkit.test", "Str0ngPass!", "9000000000").bootstrap())
                .isEqualTo(Outcome.ADMIN_EXISTS);
        verify(users, never()).save(any());
    }

    @Test
    void skipsWhenNotConfigured() {
        assertThat(bootstrap("", "", "").bootstrap()).isEqualTo(Outcome.NOT_CONFIGURED);
        assertThat(bootstrap(null, null, null).bootstrap()).isEqualTo(Outcome.NOT_CONFIGURED);
        verify(users, never()).save(any());
    }

    @Test
    void neverPromotesAnExistingAccount() {
        when(users.existsByEmailIgnoreCase("taken@parkit.test")).thenReturn(true);
        assertThat(bootstrap("taken@parkit.test", "Str0ngPass!", "9000000000").bootstrap())
                .isEqualTo(Outcome.EMAIL_IN_USE);
        verify(users, never()).save(any());
    }

    @Test
    void rejectsWeakPassword() {
        assertThat(bootstrap("root@parkit.test", "short", "9000000000").bootstrap())
                .isEqualTo(Outcome.INVALID_CONFIGURATION);
        verify(users, never()).save(any());
    }

    @Test
    void propertiesToStringMasksPassword() {
        assertThat(new AdminProperties("a@b.c", "secret-value", "A", "1").toString())
                .doesNotContain("secret-value").contains("****");
    }
}
