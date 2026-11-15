package com.parkit.service;

import com.parkit.dto.response.HealthResponse;
import java.time.Clock;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Checks application health: PostgreSQL connectivity and the applied
 * Flyway schema version. Failure details are logged, never returned.
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final JdbcTemplate jdbcTemplate;
    private final Flyway flyway;
    private final Clock clock;

    public HealthService(JdbcTemplate jdbcTemplate, Flyway flyway) {
        this.jdbcTemplate = jdbcTemplate;
        this.flyway = flyway;
        this.clock = Clock.systemUTC();
    }

    public HealthResponse check() {
        Instant now = Instant.now(clock);
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) {
                return down(now);
            }
            MigrationInfo current = flyway.info().current();
            String schemaVersion = current == null ? null : current.getVersion().getVersion();
            return new HealthResponse(HealthResponse.UP, HealthResponse.UP, schemaVersion, now);
        } catch (RuntimeException ex) {
            log.warn("Health check: database unreachable: {}", ex.getMessage());
            return down(now);
        }
    }

    private static HealthResponse down(Instant now) {
        return new HealthResponse(HealthResponse.DOWN, HealthResponse.DOWN, null, now);
    }
}
