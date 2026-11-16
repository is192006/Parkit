package com.parkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import com.parkit.service.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 4 API tests (FR-15 to FR-21, FR-32, BR-22, BR-27) against real
 * PostgreSQL with the Flyway schema. Bookings used as conflicts are inserted
 * with SQL because booking creation is a later phase.
 */
class ParkingManagementApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired
    private AuditService auditService;

    @Autowired
    private DataSource dataSource;

    private MockHttpSession admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminSession();
    }

    /** Rates and facility settings are shared rows: restore the V3 seed values after each test. */
    @AfterEach
    void restoreSeedData() {
        jdbc.update("UPDATE parking_rates SET hourly_rate = 40.00, daily_rate = 200.00 WHERE vehicle_type = 'CAR'");
        jdbc.update("UPDATE parking_rates SET hourly_rate = 20.00, daily_rate = 100.00 WHERE vehicle_type = 'BIKE'");
        jdbc.update("""
                UPDATE facility_settings SET facility_name = 'ParkIt', opening_time = TIME '00:00',
                       closing_time = TIME '00:00', open_24_hours = TRUE WHERE setting_id = 1
                """);
    }

    // ================================================================ slots

    @Test
    void adminCreatesListsAndUpdatesSlotWithAudit() throws Exception {
        String number = uniqueSlotNumber();
        int sort = nextSortOrder();
        JsonNode created = body(createSlot(number, sort, "CAR")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operationalStatus").value("ACTIVE"))
                .andReturn());
        long id = created.get("id").asLong();
        assertThat(auditCount("SLOT_CREATED", "PARKING_SLOT", id)).isEqualTo(1);

        mvc.perform(get("/api/admin/slots").param("size", "500").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)].slotNumber".formatted(id)).value(number));

        String newNumber = uniqueSlotNumber();
        updateSlot(id, newNumber, sort, "BIKE", "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotNumber").value(newNumber))
                .andExpect(jsonPath("$.vehicleType").value("BIKE"));
        assertThat(auditCount("SLOT_UPDATED", "PARKING_SLOT", id)).isEqualTo(1);

        // A no-op update is not audited.
        updateSlot(id, newNumber, sort, "BIKE", "ACTIVE").andExpect(status().isOk());
        assertThat(auditCount("SLOT_UPDATED", "PARKING_SLOT", id)).isEqualTo(1);

        updateSlot(id, newNumber, sort, "BIKE", "INACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationalStatus").value("INACTIVE"));
        assertThat(auditCount("SLOT_STATUS_CHANGED", "PARKING_SLOT", id)).isEqualTo(1);
    }

    @Test
    void slotNumberAndSortOrderMustBeUnique() throws Exception {
        String number = uniqueSlotNumber();
        int sort = nextSortOrder();
        createSlot(number, sort, "CAR").andExpect(status().isCreated());

        createSlot(number, nextSortOrder(), "CAR")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_RESOURCE"));
        createSlot(uniqueSlotNumber(), sort, "CAR")
                .andExpect(status().isConflict());

        long other = slotId(createSlot(uniqueSlotNumber(), nextSortOrder(), "CAR"));
        updateSlot(other, number, nextSortOrder(), "CAR", "ACTIVE").andExpect(status().isConflict());
        updateSlot(other, uniqueSlotNumber(), sort, "CAR", "ACTIVE").andExpect(status().isConflict());
    }

    @Test
    void invalidSlotRequestsAreRejected() throws Exception {
        createSlot(" ", nextSortOrder(), "CAR").andExpect(status().isBadRequest());
        createSlot(uniqueSlotNumber(), 0, "CAR").andExpect(status().isBadRequest());
        createSlot("X".repeat(21), nextSortOrder(), "CAR").andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/slots").with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotNumber\":\"A1\",\"sortOrder\":1,\"vehicleType\":\"TRUCK\"}"))
                .andExpect(status().isBadRequest());
        updateSlot(999_999_999L, uniqueSlotNumber(), nextSortOrder(), "CAR", "ACTIVE")
                .andExpect(status().isNotFound());
    }

    @Test
    void slotWithFutureBookingCannotBeDeactivatedOrRecategorized() throws Exception {
        int sort = nextSortOrder();
        String number = uniqueSlotNumber();
        long slot = slotId(createSlot(number, sort, "CAR"));
        Instant start = futureHour(2);
        insertBooking(slot, "CONFIRMED", start, start.plus(2, ChronoUnit.HOURS), null);

        updateSlot(slot, number, sort, "CAR", "INACTIVE")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_RULE_VIOLATION"));
        updateSlot(slot, number, sort, "BIKE", "ACTIVE").andExpect(status().isUnprocessableEntity());
        // Other fields can still change.
        updateSlot(slot, uniqueSlotNumber(), sort, "CAR", "ACTIVE").andExpect(status().isOk());
    }

    @Test
    void slotWithCheckedInVehicleCannotBeDeactivated() throws Exception {
        int sort = nextSortOrder();
        String number = uniqueSlotNumber();
        long slot = slotId(createSlot(number, sort, "CAR"));
        // Overstaying: scheduled end already passed, still CHECKED_IN.
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(5, ChronoUnit.HOURS);
        insertBooking(slot, "CHECKED_IN", start, start.plus(2, ChronoUnit.HOURS), start);

        updateSlot(slot, number, sort, "CAR", "INACTIVE").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void slotWithOnlyFinishedOrExpiredBookingsCanBeDeactivated() throws Exception {
        int sort = nextSortOrder();
        String number = uniqueSlotNumber();
        long slot = slotId(createSlot(number, sort, "CAR"));
        Instant future = futureHour(2);
        insertBooking(slot, "CANCELLED", future, future.plus(2, ChronoUnit.HOURS), null);
        insertExpiredHold(slot, future.plus(3, ChronoUnit.HOURS), future.plus(5, ChronoUnit.HOURS));

        updateSlot(slot, number, sort, "CAR", "INACTIVE").andExpect(status().isOk());
    }

    // =============================================================== blocks

    @Test
    void adminBlocksAndUnblocksSlotWithAudit() throws Exception {
        long slot = newSlot();
        LocalDateTime start = localFutureHour(24);
        LocalDateTime end = start.plusHours(3);

        JsonNode block = body(createBlock(slot, start, end, "Resurfacing")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.startTimeLocal").value(start.toString().substring(0, 16) + ":00"))
                .andReturn());
        long blockId = block.get("id").asLong();
        // Facility-local input is stored as the matching UTC instant (BR-28).
        assertThat(Instant.parse(block.get("startTime").asText())).isEqualTo(start.atZone(IST).toInstant());
        assertThat(auditCount("SLOT_BLOCK_CREATED", "SLOT_BLOCK", blockId)).isEqualTo(1);

        mvc.perform(get("/api/admin/slots/{id}/blocks", slot).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(post("/api/admin/blocks/{id}/cancel", blockId).with(csrf()).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
        assertThat(auditCount("SLOT_BLOCK_CANCELLED", "SLOT_BLOCK", blockId)).isEqualTo(1);

        // The record is kept; cancelling again is an invalid transition.
        mvc.perform(post("/api/admin/blocks/{id}/cancel", blockId).with(csrf()).session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
        mvc.perform(get("/api/admin/slots/{id}/blocks", slot).session(admin))
                .andExpect(jsonPath("$[0].active").value(false));
        mvc.perform(post("/api/admin/blocks/{id}/cancel", 999_999_999L).with(csrf()).session(admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void overlappingActiveBlocksAreRefusedButCancelledAndAdjacentOnesAreNot() throws Exception {
        long slot = newSlot();
        LocalDateTime start = localFutureHour(24);
        long first = body(createBlock(slot, start, start.plusHours(4), "First")
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        createBlock(slot, start.plusHours(3), start.plusHours(6), "Overlap")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SLOT_UNAVAILABLE"));
        // Half-open intervals: a block starting exactly at the end does not overlap (BR-07).
        createBlock(slot, start.plusHours(4), start.plusHours(5), "Adjacent").andExpect(status().isCreated());
        // The same period on another slot is fine.
        createBlock(newSlot(), start, start.plusHours(4), "Other slot").andExpect(status().isCreated());

        mvc.perform(post("/api/admin/blocks/{id}/cancel", first).with(csrf()).session(admin))
                .andExpect(status().isOk());
        createBlock(slot, start.plusHours(1), start.plusHours(3), "After cancel").andExpect(status().isCreated());
    }

    @Test
    void blockTimesAreValidated() throws Exception {
        long slot = newSlot();
        LocalDateTime now = LocalDateTime.now(IST).truncatedTo(ChronoUnit.MINUTES);

        // End before or equal to start -> 400 (request validation).
        createBlock(slot, now.plusHours(5), now.plusHours(4), "Bad").andExpect(status().isBadRequest());
        createBlock(slot, now.plusHours(5), now.plusHours(5), "Bad").andExpect(status().isBadRequest());
        // End not after now -> 422.
        createBlock(slot, now.minusHours(3), now.minusHours(1), "Past")
                .andExpect(status().isUnprocessableEntity());
        // Start in the past is allowed ("blocked from now").
        createBlock(slot, now.minusHours(1), now.plusHours(2), "From now").andExpect(status().isCreated());
        // Missing reason -> 400; unknown slot -> 404.
        createBlock(slot, now.plusDays(2), now.plusDays(2).plusHours(1), " ").andExpect(status().isBadRequest());
        createBlock(999_999_999L, now.plusDays(2), now.plusDays(2).plusHours(1), "x")
                .andExpect(status().isNotFound());
    }

    @Test
    void blockConflictingWithConfirmedBookingOrActiveHoldIsRefused() throws Exception {
        long slot = newSlot();
        Instant start = futureHour(48);
        insertBooking(slot, "CONFIRMED", start, start.plus(2, ChronoUnit.HOURS), null);
        Instant holdStart = start.plus(4, ChronoUnit.HOURS);
        insertBooking(slot, "PENDING_PAYMENT", holdStart, holdStart.plus(2, ChronoUnit.HOURS), null);

        createBlock(slot, local(start.plus(1, ChronoUnit.HOURS)), local(start.plus(3, ChronoUnit.HOURS)), "x")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SLOT_UNAVAILABLE"));
        createBlock(slot, local(holdStart), local(holdStart.plus(1, ChronoUnit.HOURS)), "x")
                .andExpect(status().isConflict());
        // Between the two bookings (adjacent on both sides) is free.
        createBlock(slot, local(start.plus(2, ChronoUnit.HOURS)), local(holdStart), "gap")
                .andExpect(status().isCreated());
    }

    @Test
    void expiredHoldAndCancelledBookingDoNotConflictWithBlock() throws Exception {
        long slot = newSlot();
        Instant start = futureHour(48);
        insertExpiredHold(slot, start, start.plus(2, ChronoUnit.HOURS));
        insertBooking(slot, "CANCELLED", start.plus(2, ChronoUnit.HOURS), start.plus(4, ChronoUnit.HOURS), null);

        createBlock(slot, local(start), local(start.plus(4, ChronoUnit.HOURS)), "free")
                .andExpect(status().isCreated());
    }

    @Test
    void overstayingVehicleConflictsWithAnyFutureBlock() throws Exception {
        long slot = newSlot();
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(5, ChronoUnit.HOURS);
        insertBooking(slot, "CHECKED_IN", start, start.plus(2, ChronoUnit.HOURS), start);

        // Overstay occupies [actual_check_in, infinity) until check-out (BR-08).
        Instant later = futureHour(72);
        createBlock(slot, local(later), local(later.plus(1, ChronoUnit.HOURS)), "x")
                .andExpect(status().isConflict());
    }

    @Test
    void earlyCheckedInVehicleConflictsFromItsActualCheckIn() throws Exception {
        long slot = newSlot();
        Instant scheduledStart = futureHour(1);
        Instant checkIn = scheduledStart.minus(30, ChronoUnit.MINUTES);
        insertBooking(slot, "CHECKED_IN", scheduledStart, scheduledStart.plus(3, ChronoUnit.HOURS), checkIn);

        // [check-in, scheduled_start) is occupied by an early check-in (BR-08).
        createBlock(slot, local(checkIn), local(scheduledStart), "x").andExpect(status().isConflict());
        // After scheduled end the slot is free (vehicle is not overstaying yet).
        Instant after = scheduledStart.plus(3, ChronoUnit.HOURS);
        createBlock(slot, local(after), local(after.plus(1, ChronoUnit.HOURS)), "x")
                .andExpect(status().isCreated());
    }

    // ================================================================ rates

    @Test
    void adminViewsAndUpdatesRatesWithAudit() throws Exception {
        mvc.perform(get("/api/admin/rates").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].vehicleType").value("CAR"))
                .andExpect(jsonPath("$[0].hourlyRate").value(40.00));

        long rateId = jdbc.queryForObject("SELECT rate_id FROM parking_rates WHERE vehicle_type = 'BIKE'", Long.class);
        long before = auditCount("RATE_UPDATED", "PARKING_RATE", rateId);
        updateRate("BIKE", "25.50", "120")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hourlyRate").value(25.50))
                .andExpect(jsonPath("$.dailyRate").value(120.00));
        assertThat(auditCount("RATE_UPDATED", "PARKING_RATE", rateId)).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("""
                SELECT details ->> 'oldHourlyRate' FROM audit_logs
                WHERE action = 'RATE_UPDATED' AND entity_id = ? ORDER BY log_id DESC LIMIT 1
                """, String.class, rateId)).isEqualTo("20.00");
        assertThat(jdbc.queryForObject(
                "SELECT updated_by_user_id IS NOT NULL FROM parking_rates WHERE rate_id = ?", Boolean.class, rateId))
                .isTrue();
    }

    @Test
    void invalidRateUpdatesAreRejected() throws Exception {
        updateRate("CAR", "0", "200").andExpect(status().isBadRequest());
        updateRate("CAR", "-5", "200").andExpect(status().isBadRequest());
        updateRate("CAR", "40.555", "200").andExpect(status().isBadRequest());
        updateRate("CAR", "40", "123456789").andExpect(status().isBadRequest());
        updateRate("TRUCK", "40", "200").andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/rates/CAR").with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hourlyRate\":40}"))
                .andExpect(status().isBadRequest());
    }

    // ===================================================== facility settings

    @Test
    void adminUpdatesFacilitySettingsAndPublicViewReflectsThem() throws Exception {
        mvc.perform(get("/api/admin/facility-settings").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open24Hours").value(true))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"));

        // Overnight window (closing earlier than opening) is valid (BR-06.5).
        updateFacility("ParkIt Central", "18:00", "02:00", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilityName").value("ParkIt Central"))
                .andExpect(jsonPath("$.openingTime").value("18:00:00"))
                .andExpect(jsonPath("$.open24Hours").value(false));
        assertThat(auditCount("FACILITY_SETTINGS_UPDATED", "FACILITY_SETTINGS", 1L)).isPositive();

        // Public endpoint, no session (FR-21).
        mvc.perform(get("/api/facility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilityName").value("ParkIt Central"))
                .andExpect(jsonPath("$.closingTime").value("02:00:00"))
                .andExpect(jsonPath("$.rates.length()").value(2))
                .andExpect(jsonPath("$.rates[1].vehicleType").value("BIKE"));
    }

    @Test
    void invalidFacilitySettingsAreRejected() throws Exception {
        // Equal opening and closing only when open 24 hours (ck_hours).
        updateFacility("ParkIt", "08:00", "08:00", false).andExpect(status().isBadRequest());
        updateFacility("ParkIt", "08:00", "08:00", true).andExpect(status().isOk());
        updateFacility(" ", "08:00", "20:00", false).andExpect(status().isBadRequest());
        updateFacility("X".repeat(151), "08:00", "20:00", false).andExpect(status().isBadRequest());
        updateFacility("ParkIt", "25:00", "20:00", false).andExpect(status().isBadRequest());
    }

    // ============================================================= security

    @Test
    void parkingManagementEndpointsRequireAdmin() throws Exception {
        MockHttpSession customer = newCustomerSession();
        mvc.perform(get("/api/admin/slots").session(customer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/rates").session(customer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/facility-settings").session(customer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/slots").with(csrf()).session(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotJson(uniqueSlotNumber(), nextSortOrder(), "CAR")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/blocks/1/cancel").with(csrf()).session(customer))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/slots")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/rates")).andExpect(status().isUnauthorized());
        // The public facility view is the only anonymous endpoint here.
        mvc.perform(get("/api/facility")).andExpect(status().isOk());
    }

    @Test
    void stateChangingAdminRequestsRequireCsrf() throws Exception {
        mvc.perform(post("/api/admin/slots").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotJson(uniqueSlotNumber(), nextSortOrder(), "CAR")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/rates/CAR").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hourlyRate\":50,\"dailyRate\":250}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject(
                "SELECT hourly_rate FROM parking_rates WHERE vehicle_type = 'CAR'", java.math.BigDecimal.class))
                .isEqualByComparingTo("40.00");
    }

    @Test
    void auditServiceRefusesToWriteOutsideATransaction() {
        // Propagation.MANDATORY: an audit row can only be written together with the audited change.
        assertThatThrownBy(() -> auditService.recordUserAction(1L, "X", "Y", 1L, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ========================================================= lock timeout

    @Test
    void everyConnectionHasTheFiveSecondLockTimeout() {
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
    }

    /**
     * ARCHITECTURE.md §6: while another transaction holds the slot row lock, a
     * slot status change or block creation waits at most 5 s and then fails
     * with 409 "please retry"; nothing is written or audited.
     */
    @Test
    void lockedSlotTimesOutWith409AndWritesNothing() throws Exception {
        int sort = nextSortOrder();
        String number = uniqueSlotNumber();
        long slot = slotId(createSlot(number, sort, "CAR"));
        LocalDateTime start = localFutureHour(24);

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT slot_id FROM parking_slots WHERE slot_id = ? FOR UPDATE")) {
                lock.setLong(1, slot);
                lock.executeQuery();
            }

            long t0 = System.nanoTime();
            updateSlot(slot, number, sort, "CAR", "INACTIVE")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("CONCURRENCY_CONFLICT"));
            Duration waited = Duration.ofNanos(System.nanoTime() - t0);
            assertThat(waited).isBetween(Duration.ofMillis(4500), Duration.ofSeconds(15));

            createBlock(slot, start, start.plusHours(1), "x")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("CONCURRENCY_CONFLICT"));
            other.rollback();
        }

        assertThat(auditCount("SLOT_STATUS_CHANGED", "PARKING_SLOT", slot)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_blocks WHERE slot_id = ?", Long.class, slot))
                .isZero();
        // Once the lock is released, the same requests succeed.
        updateSlot(slot, number, sort, "CAR", "INACTIVE").andExpect(status().isOk());
        createBlock(slot, start, start.plusHours(1), "x").andExpect(status().isCreated());
    }

    // ============================================================== helpers

    private ResultActions createSlot(String number, int sort, String type) throws Exception {
        return mvc.perform(post("/api/admin/slots").with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON).content(slotJson(number, sort, type)));
    }

    private ResultActions updateSlot(long id, String number, int sort, String type, String status) throws Exception {
        return mvc.perform(put("/api/admin/slots/{id}", id).with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"slotNumber":"%s","sortOrder":%d,"vehicleType":"%s","operationalStatus":"%s"}
                        """.formatted(number, sort, type, status)));
    }

    private ResultActions createBlock(long slot, LocalDateTime start, LocalDateTime end, String reason)
            throws Exception {
        return mvc.perform(post("/api/admin/slots/{id}/blocks", slot).with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"startTime\":\"%s\",\"endTime\":\"%s\",\"reason\":\"%s\"}"
                        .formatted(start, end, reason)));
    }

    private ResultActions updateRate(String type, String hourly, String daily) throws Exception {
        return mvc.perform(put("/api/admin/rates/{type}", type).with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hourlyRate\":%s,\"dailyRate\":%s}".formatted(hourly, daily)));
    }

    private ResultActions updateFacility(String name, String open, String close, boolean allDay) throws Exception {
        return mvc.perform(put("/api/admin/facility-settings").with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"facilityName":"%s","openingTime":"%s","closingTime":"%s","open24Hours":%s}
                        """.formatted(name, open, close, allDay)));
    }

    private static String slotJson(String number, int sort, String type) {
        return "{\"slotNumber\":\"%s\",\"sortOrder\":%d,\"vehicleType\":\"%s\"}".formatted(number, sort, type);
    }

    private long slotId(ResultActions result) throws Exception {
        return body(result.andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private long newSlot() throws Exception {
        return slotId(createSlot(uniqueSlotNumber(), nextSortOrder(), "CAR"));
    }

    private static String uniqueSlotNumber() {
        return "S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private int nextSortOrder() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM parking_slots", Integer.class);
    }

    private long auditCount(String action, String entityType, long entityId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_type = ? AND entity_id = ?",
                Long.class, action, entityType, entityId);
    }

    /** A whole hour at least {@code hours} from now (on the 15-minute grid). */
    private static Instant futureHour(int hours) {
        return Instant.now().truncatedTo(ChronoUnit.HOURS).plus(hours + 1L, ChronoUnit.HOURS);
    }

    private static LocalDateTime localFutureHour(int hours) {
        return local(futureHour(hours));
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, IST);
    }

    /** Inserts a customer with a new vehicle and returns {userId, vehicleId}. */
    private long[] insertCustomerWithVehicle() {
        long userId = jdbc.queryForObject("""
                INSERT INTO users (name, email, password_hash, phone) VALUES ('Booker', ?, 'x', '9876543210')
                RETURNING user_id
                """, Long.class, uniqueEmail());
        long vehicleId = jdbc.queryForObject("""
                INSERT INTO vehicles (user_id, registration_number, vehicle_type) VALUES (?, ?, 'CAR')
                RETURNING vehicle_id
                """, Long.class, userId, uniqueRegistration());
        return new long[] {userId, vehicleId};
    }

    /** Inserts a booking satisfying the status consistency CHECKs (DATABASE_DESIGN.md §5). */
    private void insertBooking(long slotId, String status, Instant start, Instant end, Instant checkIn) {
        long[] owner = insertCustomerWithVehicle();
        boolean pending = status.equals("PENDING_PAYMENT");
        boolean cancelled = status.equals("CANCELLED");
        Instant holdExpiry = pending ? Instant.now().plus(5, ChronoUnit.MINUTES) : Instant.now();
        jdbc.update("""
                INSERT INTO bookings (booking_reference, user_id, vehicle_id, slot_id, created_by_user_id,
                    scheduled_start, scheduled_end, hold_expires_at, confirmed_at, actual_check_in,
                    hourly_rate_snapshot, daily_rate_snapshot, booking_amount, booking_status,
                    cancelled_at, cancellation_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 40, 200, 80, ?, ?, ?)
                """,
                "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), owner[0], owner[1], slotId,
                owner[0], Timestamp.from(start), Timestamp.from(end), Timestamp.from(holdExpiry),
                pending ? null : Timestamp.from(Instant.now()),
                checkIn == null ? null : Timestamp.from(checkIn), status,
                cancelled ? Timestamp.from(Instant.now()) : null, cancelled ? "test" : null);
    }

    /** PENDING_PAYMENT whose hold has already expired but was not yet swept (BR-08). */
    private void insertExpiredHold(long slotId, Instant start, Instant end) {
        long[] owner = insertCustomerWithVehicle();
        jdbc.update("""
                INSERT INTO bookings (booking_reference, user_id, vehicle_id, slot_id, created_by_user_id,
                    scheduled_start, scheduled_end, hold_expires_at,
                    hourly_rate_snapshot, daily_rate_snapshot, booking_amount, booking_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 40, 200, 80, 'PENDING_PAYMENT')
                """,
                "T-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), owner[0], owner[1], slotId,
                owner[0], Timestamp.from(start), Timestamp.from(end),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));
    }
}
