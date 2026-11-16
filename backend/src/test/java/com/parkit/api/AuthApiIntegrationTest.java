package com.parkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** Registration, session login/logout, CSRF, roles, profile and password (FR-01 to FR-08). */
class AuthApiIntegrationTest extends AbstractApiIntegrationTest {

    // ---------- registration ----------

    @Test
    void registrationCreatesCustomerWithBcryptHashAndNoPasswordInResponse() throws Exception {
        String email = uniqueEmail();
        MvcResult result = register(email, PASSWORD);

        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain(PASSWORD).doesNotContain("password");
        assertThat(body(result).get("role").asText()).isEqualTo("CUSTOMER");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT role, password_hash FROM users WHERE lower(email) = lower(?)", email);
        assertThat(row.get("role")).isEqualTo("CUSTOMER");
        assertThat((String) row.get("password_hash")).startsWith("$2").isNotEqualTo(PASSWORD);
    }

    @Test
    void registrationIgnoresRoleSuppliedByClient() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","email":"%s","password":"%s","phone":"9876543210","role":"ADMIN"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCase() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","email":"%s","password":"%s","phone":"9876543210"}
                                """.formatted(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void invalidRegistrationReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","email":"not-an-email","password":"short","phone":"abc"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists())
                .andExpect(jsonPath("$.fieldErrors.passwordValid").exists());
    }

    // ---------- login / logout / session ----------

    @Test
    void loginCreatesSessionAndMeReturnsUser() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        MockHttpSession session = login(email.toUpperCase(), PASSWORD);

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveSameGeneric401() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        for (MvcResult r : new MvcResult[] {loginResult(email, "WrongPass1"), loginResult(uniqueEmail(), PASSWORD)}) {
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(r).get("errorCode").asText()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(body(r).get("detail").asText()).isEqualTo("Invalid email or password.");
        }
    }

    @Test
    void deactivatedUserCannotLogIn() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        jdbc.update("UPDATE users SET is_active = FALSE WHERE lower(email) = lower(?)", email);
        MvcResult r = loginResult(email, PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void sessionIdChangesOnLogin() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(before);
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = newCustomerSession();
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousRequestGets401ProblemDetail() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    // ---------- CSRF ----------

    @Test
    void stateChangingRequestWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"X","email":"%s","password":"%s","phone":"9876543210"}
                                """.formatted(uniqueEmail(), PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CSRF_INVALID"));
    }

    // ---------- roles ----------

    @Test
    void adminIsBootstrappedFromConfiguration() throws Exception {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT role, password_hash FROM users WHERE lower(email) = lower(?)", ADMIN_EMAIL);
        assertThat(row.get("role")).isEqualTo("ADMIN");
        assertThat((String) row.get("password_hash")).startsWith("$2").isNotEqualTo(ADMIN_PASSWORD);

        mvc.perform(get("/api/auth/me").session(adminSession()))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void customerCannotAccessAdminEndpoints() throws Exception {
        mvc.perform(get("/api/admin/customers").session(newCustomerSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void anonymousCannotAccessAdminEndpoints() throws Exception {
        mvc.perform(get("/api/admin/customers")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanSearchCustomersButAdminsAreNotListed() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String token = email.substring(5, 13);
        mvc.perform(get("/api/admin/customers").param("q", token).session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(email));
        mvc.perform(get("/api/admin/customers").param("q", "admin@parkit").session(adminSession()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void adminCannotUseCustomerVehicleEndpoints() throws Exception {
        mvc.perform(get("/api/vehicles").session(adminSession())).andExpect(status().isForbidden());
    }

    // ---------- profile and password ----------

    @Test
    void profileUpdateChangesNameAndPhoneButNotEmail() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        MockHttpSession session = login(email, PASSWORD);

        mvc.perform(put("/api/users/me").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\",\"phone\":\"+91 98765-43210\",\"email\":\"x@evil.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"))
                .andExpect(jsonPath("$.phone").value("+91 98765-43210"))
                .andExpect(jsonPath("$.email").value(email));

        mvc.perform(get("/api/users/me").session(session))
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    void invalidProfileUpdateIsRejected() throws Exception {
        mvc.perform(put("/api/users/me").session(newCustomerSession()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"phone\":\"12\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndTakesEffect() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        MockHttpSession session = login(email, PASSWORD);

        mvc.perform(put("/api/users/me/password").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"WrongPass1\",\"newPassword\":\"NewSecret456\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CURRENT_PASSWORD"));

        mvc.perform(put("/api/users/me/password").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"NewSecret456\"}".formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        assertThat(loginResult(email, PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(loginResult(email, "NewSecret456").getResponse().getStatus()).isEqualTo(200);
    }
}
