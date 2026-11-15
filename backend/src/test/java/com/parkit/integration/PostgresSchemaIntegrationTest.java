package com.parkit.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkit.dto.response.HealthResponse;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-context integration test against a real PostgreSQL 16 container:
 * Flyway applies the real migrations, Hibernate validates, the health
 * endpoint reports UP, and key database safety nets work.
 *
 * <p>Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresSchemaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void cleanBusinessTables() {
        // DELETE (not TRUNCATE ... CASCADE) so the V3 seed rows in parking_rates and
        // facility_settings, which reference users, are preserved.
        jdbc.execute("DELETE FROM bookings; DELETE FROM vehicles; DELETE FROM parking_slots; DELETE FROM users");
    }

    @Test
    void flywayAppliedAllMigrations() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void allTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertThat(tables).contains("users", "vehicles", "parking_slots", "bookings", "payments",
                "refunds", "parking_rates", "slot_blocks", "facility_settings", "audit_logs");
    }

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<HealthResponse> response = rest.getForEntity("/api/health", HealthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().schemaVersion()).isEqualTo("3");
    }

    @Test
    void seedDataMatchesApprovedValues() {
        Map<String, Object> facility = jdbc.queryForMap(
                "SELECT facility_name, opening_time::text AS open, closing_time::text AS close, "
                        + "open_24_hours, timezone, updated_by_user_id FROM facility_settings");
        assertThat(facility).containsEntry("facility_name", "ParkIt")
                .containsEntry("open", "00:00:00")
                .containsEntry("close", "00:00:00")
                .containsEntry("open_24_hours", true)
                .containsEntry("timezone", "Asia/Kolkata")
                .containsEntry("updated_by_user_id", null);

        List<String> rates = jdbc.queryForList(
                "SELECT vehicle_type || ':' || hourly_rate || ':' || daily_rate FROM parking_rates ORDER BY vehicle_type",
                String.class);
        assertThat(rates).containsExactly("BIKE:20.00:100.00", "CAR:40.00:200.00");
    }

    @Test
    void emailUniquenessIsCaseInsensitive() {
        insertUser("a@example.com");
        assertThatThrownBy(() -> insertUser("A@Example.COM"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exclusionConstraintRejectsOverlappingSlotBookings() {
        long user = insertUser("c@example.com");
        long v1 = insertVehicle(user, "KA01AB1234");
        long v2 = insertVehicle(user, "KA01AB9999");
        long slot = jdbc.queryForObject(
                "INSERT INTO parking_slots (slot_number, sort_order, vehicle_type) "
                        + "VALUES ('C-01', 1, 'CAR') RETURNING slot_id",
                Long.class);

        insertBooking("ref1", user, v1, slot, "2026-12-01T04:00:00Z", "2026-12-01T06:00:00Z");
        // Adjacent interval [06:00, 07:00) does not overlap (half-open, BR-07).
        insertBooking("ref2", user, v2, slot, "2026-12-01T06:00:00Z", "2026-12-01T07:00:00Z");

        assertThatThrownBy(() -> insertBooking("ref3", user, v2, slot,
                "2026-12-01T05:00:00Z", "2026-12-01T06:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_slot_overlap");
    }

    private long insertUser(String email) {
        return jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash, phone) "
                        + "VALUES ('Test', ?, 'x', '9999999999') RETURNING user_id",
                Long.class, email);
    }

    private long insertVehicle(long userId, String reg) {
        return jdbc.queryForObject(
                "INSERT INTO vehicles (user_id, registration_number, vehicle_type) "
                        + "VALUES (?, ?, 'CAR') RETURNING vehicle_id",
                Long.class, userId, reg);
    }

    private void insertBooking(String ref, long user, long vehicle, long slot, String start, String end) {
        jdbc.update("""
                INSERT INTO bookings (booking_reference, user_id, vehicle_id, slot_id, created_by_user_id,
                    scheduled_start, scheduled_end, hold_expires_at, confirmed_at,
                    hourly_rate_snapshot, daily_rate_snapshot, booking_amount, booking_status)
                VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, now(), now(), 30, 200, 60, 'CONFIRMED')
                """, ref, user, vehicle, slot, user, start, end);
    }
}
