package com.parkit.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkit.config.SecurityConfig;
import com.parkit.dto.response.HealthResponse;
import com.parkit.service.HealthService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer slice test for {@link HealthController}; the service is mocked. */
@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Test
    void returns200WhenUp() throws Exception {
        when(healthService.check()).thenReturn(
                new HealthResponse("UP", "UP", "2", Instant.parse("2026-11-15T10:00:00Z")));
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.schemaVersion").value("2"))
                .andExpect(jsonPath("$.timestamp").value("2026-11-15T10:00:00Z"))
                .andExpect(jsonPath("$.up").doesNotExist());
    }

    @Test
    void returns503WhenDatabaseDown() throws Exception {
        when(healthService.check()).thenReturn(
                new HealthResponse("DOWN", "DOWN", null, Instant.parse("2026-11-15T10:00:00Z")));
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
