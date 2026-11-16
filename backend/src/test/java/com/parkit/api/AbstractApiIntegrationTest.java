package com.parkit.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared base for full-stack API tests: one PostgreSQL 16 container and one
 * Spring context for all subclasses. Tests use unique emails, so no cleanup
 * is needed. The first admin is provisioned through AdminBootstrap from the
 * test-only properties below (in production they come from the environment).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "parkit.admin.email=" + AbstractApiIntegrationTest.ADMIN_EMAIL,
        "parkit.admin.password=" + AbstractApiIntegrationTest.ADMIN_PASSWORD,
        "parkit.admin.phone=9000000000"
})
@AutoConfigureMockMvc
abstract class AbstractApiIntegrationTest {

    static final String ADMIN_EMAIL = "admin@parkit.test";
    static final String ADMIN_PASSWORD = "Admin-Test-Pass-1";
    static final String PASSWORD = "Secret123";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    protected static String uniqueRegistration() {
        return "KA" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    protected MvcResult register(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test User","email":"%s","password":"%s","phone":"9876543210"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    protected MvcResult loginResult(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn();
    }

    protected MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = loginResult(email, password);
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("login failed: " + result.getResponse().getContentAsString());
        }
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** Registers a new customer and returns a logged-in session. */
    protected MockHttpSession newCustomerSession() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        return login(email, PASSWORD);
    }

    protected MockHttpSession adminSession() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
