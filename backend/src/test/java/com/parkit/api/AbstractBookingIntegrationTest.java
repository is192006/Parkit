package com.parkit.api;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Base for booking tests (Phase 5).
 *
 * <ul>
 *   <li><b>Controllable clock:</b> a {@link MutableClock} replaces the application
 *       Clock, starting at {@link #BASE} (2030-01-07 10:00 IST), so hold expiry
 *       is tested by advancing time instead of waiting 5 minutes.</li>
 *   <li><b>Isolated inventory:</b> allocation considers every ACTIVE slot in the
 *       shared test database, so each test first marks all existing slots
 *       INACTIVE (direct SQL, bypassing the BR-22 check on purpose) and creates
 *       its own. Rates and facility settings are restored after each test.</li>
 * </ul>
 */
@Import(AbstractBookingIntegrationTest.ClockTestConfig.class)
abstract class AbstractBookingIntegrationTest extends AbstractApiIntegrationTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 2030-01-07 10:00 in the facility time zone. */
    static final Instant BASE = LocalDateTime.of(2030, 1, 7, 10, 0).atZone(IST).toInstant();

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetWorld() {
        clock.set(BASE);
        jdbc.update("UPDATE parking_slots SET operational_status = 'INACTIVE'");
    }

    @AfterEach
    void restoreSeed() {
        jdbc.update("""
                UPDATE facility_settings SET facility_name = 'ParkIt', opening_time = TIME '00:00',
                       closing_time = TIME '00:00', open_24_hours = TRUE WHERE setting_id = 1
                """);
        jdbc.update("UPDATE parking_rates SET hourly_rate = 40.00, daily_rate = 200.00 WHERE vehicle_type = 'CAR'");
        jdbc.update("UPDATE parking_rates SET hourly_rate = 20.00, daily_rate = 100.00 WHERE vehicle_type = 'BIKE'");
    }

    // ---------------------------------------------------------------- data

    protected long newSlot(String type) {
        int sort = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM parking_slots", Integer.class);
        return jdbc.queryForObject("""
                INSERT INTO parking_slots (slot_number, sort_order, vehicle_type) VALUES (?, ?, ?) RETURNING slot_id
                """, Long.class, "B-" + UUID.randomUUID().toString().substring(0, 8), sort, type);
    }

    protected long userId(String email) {
        return jdbc.queryForObject("SELECT user_id FROM users WHERE lower(email) = lower(?)", Long.class, email);
    }

    protected long newCustomerId() {
        return jdbc.queryForObject("""
                INSERT INTO users (name, email, password_hash, phone) VALUES ('Cust', ?, 'x', '9876543210')
                RETURNING user_id
                """, Long.class, uniqueEmail());
    }

    protected long newVehicle(long ownerId, String type) {
        return jdbc.queryForObject("""
                INSERT INTO vehicles (user_id, registration_number, vehicle_type) VALUES (?, ?, ?) RETURNING vehicle_id
                """, Long.class, ownerId, uniqueRegistration(), type);
    }

    /**
     * Inserts a booking row directly (statuses not reachable through Phase 5 APIs).
     * Satisfies the status CHECK constraints (DATABASE_DESIGN.md §5).
     */
    protected long insertBooking(long slotId, String status, Instant start, Instant end,
                                 Instant confirmedAt, Instant checkIn, Instant holdExpiresAt) {
        long customer = newCustomerId();
        long vehicle = newVehicle(customer, "CAR");
        boolean cancelled = status.equals("CANCELLED");
        boolean completed = status.equals("COMPLETED");
        return jdbc.queryForObject("""
                INSERT INTO bookings (booking_reference, user_id, vehicle_id, slot_id, created_by_user_id,
                    scheduled_start, scheduled_end, hold_expires_at, confirmed_at, actual_check_in, actual_check_out,
                    hourly_rate_snapshot, daily_rate_snapshot, booking_amount, booking_status,
                    cancelled_at, cancellation_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 40, 200, 80, ?, ?, ?) RETURNING booking_id
                """, Long.class,
                "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), customer, vehicle, slotId,
                customer, ts(start), ts(end), ts(holdExpiresAt == null ? start : holdExpiresAt), ts(confirmedAt),
                ts(checkIn), completed ? ts(end) : null, status,
                cancelled ? ts(clock.instant()) : null, cancelled ? "test" : null);
    }

    /** A CANCELLED booking that was confirmed at {@code confirmedAt}: counts for balancing (BR-09) but occupies nothing. */
    protected void addConfirmedHistory(long slotId, Instant confirmedAt) {
        Instant start = BASE.plus(20, ChronoUnit.DAYS);
        insertBooking(slotId, "CANCELLED", start, start.plus(1, ChronoUnit.HOURS), confirmedAt, null, null);
    }

    protected static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    /** BASE + hours, facility-local. */
    protected static LocalDateTime at(long hoursFromBase) {
        return LocalDateTime.ofInstant(BASE.plus(hoursFromBase, ChronoUnit.HOURS), IST);
    }

    protected static Instant instantAt(long hoursFromBase) {
        return BASE.plus(hoursFromBase, ChronoUnit.HOURS);
    }

    // --------------------------------------------------------------- clock

    /** A settable Clock for tests; thread-safe. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(BASE);

        void set(Instant instant) {
            now.set(instant);
        }

        void advance(java.time.Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
