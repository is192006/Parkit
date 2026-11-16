package com.parkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkit.service.BookingExpiryService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/** Phase 5 API tests: quote, hold, release, expiry, history, admin holds (FR-22 to FR-31). */
@Import(AbstractBookingIntegrationTest.ClockTestConfig.class)
class BookingApiIntegrationTest extends AbstractBookingIntegrationTest {

    @Autowired
    private BookingExpiryService expiryService;

    @Autowired
    private DataSource dataSource;

    private MockHttpSession customer;
    private long customerId;
    private long car;

    @BeforeEach
    void newCustomer() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        customer = login(email, PASSWORD);
        customerId = userId(email);
        car = newVehicle(customerId, "CAR");
    }

    // ================================================================ quote

    @Test
    void quoteReturnsPriceAndAvailabilityWithoutWriting() throws Exception {
        newSlot("CAR");
        long before = bookingCount();
        quote(car, at(2), at(28))            // 26 h at 40/h, 200/day -> 200 + min(80, 200) = 280
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.price.billableHours").value(26))
                .andExpect(jsonPath("$.price.total").value(280.00))
                .andExpect(jsonPath("$.customerCancellable").value(true))
                .andExpect(jsonPath("$.startTime").value("2030-01-07T06:30:00Z"))
                .andExpect(jsonPath("$.slotNumber").doesNotExist());
        assertThat(bookingCount()).isEqualTo(before);
    }

    @Test
    void quoteReportsUnavailableAndShortNoticeWarning() throws Exception {
        long slot = newSlot("CAR");
        insertBooking(slot, "CONFIRMED", instantAt(1), instantAt(5), BASE, null, null);
        quote(car, at(1), at(3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.customerCancellable").value(false)); // starts < 2 h away (BR-13)
    }

    // ================================================================ holds

    @Test
    void createHoldAllocatesSlotAndFiveMinuteHold() throws Exception {
        long slot = newSlot("CAR");
        JsonNode hold = body(createHold(customer, car, at(3), at(5))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.recordType").value("PAYMENT_HOLD"))
                .andExpect(jsonPath("$.holdExpiresAt").value("2030-01-07T04:35:00Z"))
                .andExpect(jsonPath("$.price.total").value(80.00))
                .andExpect(jsonPath("$.startTimeLocal").value("2030-01-07T13:00:00"))
                .andReturn());
        String ref = hold.get("reference").asText();
        assertThat(ref).hasSize(22);
        assertThat(jdbc.queryForObject("SELECT slot_id FROM bookings WHERE booking_reference = ?", Long.class, ref))
                .isEqualTo(slot);
        assertThat(jdbc.queryForObject("""
                SELECT hourly_rate_snapshot = 40 AND daily_rate_snapshot = 200 AND confirmed_at IS NULL
                FROM bookings WHERE booking_reference = ?""", Boolean.class, ref)).isTrue();

        mvc.perform(get("/api/bookings/hold").session(customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reference").value(ref));
        mvc.perform(get("/api/bookings/{ref}", ref).session(customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slotNumber").isNotEmpty());
    }

    @Test
    void rateChangeAfterHoldDoesNotChangeItsPrice() throws Exception {
        newSlot("CAR");
        String ref = ref(createHold(customer, car, at(3), at(5)));
        jdbc.update("UPDATE parking_rates SET hourly_rate = 99 WHERE vehicle_type = 'CAR'");
        mvc.perform(get("/api/bookings/{ref}", ref).session(customer))
                .andExpect(jsonPath("$.price.total").value(80.00));
    }

    @Test
    void timeRulesAreEnforced() throws Exception {
        newSlot("CAR");
        createHold(customer, car, at(3).plusMinutes(10), at(5)).andExpect(status().isUnprocessableEntity());
        createHold(customer, car, at(3), at(3).plusMinutes(45)).andExpect(status().isUnprocessableEntity());
        createHold(customer, car, at(3), at(3).plusDays(7).plusMinutes(15)).andExpect(status().isUnprocessableEntity());
        createHold(customer, car, at(0).plusMinutes(15), at(2)).andExpect(status().isUnprocessableEntity());
        createHold(customer, car, at(0).plusDays(30).plusMinutes(15), at(0).plusDays(30).plusHours(2))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_RULE_VIOLATION"));
        quote(car, at(3), at(2)).andExpect(status().isUnprocessableEntity());
        assertThat(bookingCount(customerId)).isZero();
    }

    @Test
    void operatingHoursApplyIncludingOvernightWindows() throws Exception {
        newSlot("CAR");
        jdbc.update("""
                UPDATE facility_settings SET opening_time = TIME '18:00', closing_time = TIME '02:00',
                       open_24_hours = FALSE WHERE setting_id = 1""");
        LocalDateTime day = LocalDateTime.of(2030, 1, 8, 0, 0);
        quote(car, day.plusHours(2), day.plusHours(19)).andExpect(status().isUnprocessableEntity());  // start 02:00
        quote(car, day.plusHours(19), day.plusHours(42)).andExpect(status().isUnprocessableEntity()); // end 18:00
        quote(car, day.plusHours(18), day.plusHours(26)).andExpect(status().isOk());   // 18:00 -> 02:00
        // Spans the closed period (BR-06.4).
        createHold(customer, car, day.plusHours(23), day.plusHours(43)).andExpect(status().isCreated());
    }

    @Test
    void vehicleMustBeOwnedAndActive() throws Exception {
        newSlot("CAR");
        long other = newVehicle(newCustomerId(), "CAR");
        createHold(customer, other, at(3), at(5)).andExpect(status().isNotFound());
        jdbc.update("UPDATE vehicles SET is_active = FALSE WHERE vehicle_id = ?", car);
        createHold(customer, car, at(3), at(5)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void oneActiveHoldPerCustomer() throws Exception {
        newSlot("CAR");
        newSlot("CAR");
        createHold(customer, car, at(3), at(5)).andExpect(status().isCreated());
        long car2 = newVehicle(customerId, "CAR");
        createHold(customer, car2, at(10), at(12))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void vehicleCannotHaveOverlappingActiveBookings() throws Exception {
        long slot = newSlot("CAR");
        newSlot("CAR");
        jdbc.update("""
                INSERT INTO bookings (booking_reference, user_id, vehicle_id, slot_id, created_by_user_id,
                    scheduled_start, scheduled_end, hold_expires_at, confirmed_at,
                    hourly_rate_snapshot, daily_rate_snapshot, booking_amount, booking_status)
                VALUES ('VOVERLAP' || ?, ?, ?, ?, ?, ?, ?, ?, ?, 40, 200, 80, 'CONFIRMED')""",
                car, customerId, car, slot, customerId, ts(instantAt(4)), ts(instantAt(6)), ts(BASE), ts(BASE));
        createHold(customer, car, at(5), at(7)).andExpect(status().isConflict());
        quote(car, at(5), at(7)).andExpect(status().isConflict());
        createHold(customer, car, at(6), at(8)).andExpect(status().isCreated()); // adjacent is fine (BR-07)
    }

    @Test
    void noFreeSlotIsConflict() throws Exception {
        long slot = newSlot("CAR");
        insertBooking(slot, "CONFIRMED", instantAt(2), instantAt(6), BASE, null, null);
        createHold(customer, car, at(3), at(5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SLOT_UNAVAILABLE"));
        long bike = newVehicle(customerId, "BIKE");
        createHold(customer, bike, at(3), at(5)).andExpect(status().isConflict()); // no BIKE slots: CAR not used
    }

    // ============================================================== expiry

    @Test
    void expiredHoldStopsBlockingAndIsMarkedExpiredOnNextHold() throws Exception {
        newSlot("CAR");
        String first = ref(createHold(customer, car, at(3), at(5)));
        clock.advance(Duration.ofMinutes(5));    // hold_expires_at <= now: expired (BR-08)
        mvc.perform(get("/api/bookings/hold").session(customer)).andExpect(status().isNoContent());

        // Same customer, same vehicle, same (only) slot, same time: allowed now.
        String second = ref(createHold(customer, car, at(3), at(5)));
        assertThat(statusOf(first)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(statusOf(second)).isEqualTo("PENDING_PAYMENT");
        assertThat(systemAudits(first)).isEqualTo(1);
    }

    @Test
    void expirySweepExpiresOnlyOverdueHoldsIdempotently() throws Exception {
        newSlot("CAR");
        String ref = ref(createHold(customer, car, at(3), at(5)));
        clock.advance(Duration.ofMinutes(4));
        assertThat(expiryService.expireOverdueHolds()).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(expiryService.expireOverdueHolds()).isEqualTo(1);
        assertThat(expiryService.expireOverdueHolds()).isZero();
        assertThat(statusOf(ref)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(systemAudits(ref)).isEqualTo(1);
    }

    // ============================================================= release

    @Test
    void customerReleasesOwnHold() throws Exception {
        newSlot("CAR");
        String ref = ref(createHold(customer, car, at(3), at(5)));
        MockHttpSession stranger = newCustomerSession();
        release(stranger, ref).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/{ref}", ref).session(stranger)).andExpect(status().isNotFound());

        release(customer, ref)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("HOLD_RELEASED"))
                .andExpect(jsonPath("$.recordType").value("ABANDONED_PAYMENT"));
        release(customer, ref).andExpect(status().isConflict());
        mvc.perform(get("/api/bookings/hold").session(customer)).andExpect(status().isNoContent());
        // The released slot is free again.
        createHold(customer, car, at(3), at(5)).andExpect(status().isCreated());
    }

    @Test
    void releasingExpiredHoldIsGone() throws Exception {
        newSlot("CAR");
        String ref = ref(createHold(customer, car, at(3), at(5)));
        clock.advance(Duration.ofMinutes(6));
        release(customer, ref).andExpect(status().isGone());
    }

    // ============================================================= history

    @Test
    void historyKeepsAbandonedHoldsApartFromCancelledBookings() throws Exception {
        long slot = newSlot("CAR");
        newSlot("CAR");
        // Confirmed (upcoming), and confirmed-then-cancelled, for this customer.
        String upcoming = insertOwnBooking(slot, "CONFIRMED", 30, BASE, null);
        String cancelled = insertOwnBooking(slot, "CANCELLED", 40, BASE, "ADMIN_CANCELLED");
        // A released hold and an expired hold: never confirmed.
        String released = ref(createHold(customer, car, at(3), at(5)));
        release(customer, released).andExpect(status().isOk());
        String expired = ref(createHold(customer, car, at(6), at(8)));
        clock.advance(Duration.ofMinutes(5));
        expiryService.expireOverdueHolds();

        assertRefs(history("ALL"), upcoming, cancelled);
        assertRefs(history("UPCOMING"), upcoming);
        assertRefs(history("CANCELLED"), cancelled);
        assertRefs(history("ABANDONED"), released, expired);
        mvc.perform(get("/api/bookings").param("status", "ABANDONED").session(customer))
                .andExpect(jsonPath("$.content[*].recordType").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ABANDONED_PAYMENT"))));
        mvc.perform(get("/api/bookings").param("status", "CANCELLED").session(customer))
                .andExpect(jsonPath("$.content[0].recordType").value("BOOKING"));
        mvc.perform(get("/api/bookings").param("status", "BOGUS").session(customer))
                .andExpect(status().isBadRequest());
    }

    // =============================================================== admin

    @Test
    void adminCreatesHoldForCustomerAndCanFindIt() throws Exception {
        newSlot("CAR");
        MockHttpSession admin = adminSession();
        JsonNode created = body(adminHold(admin, customerId, car, at(3), at(5))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.booking.status").value("PENDING_PAYMENT"))
                .andReturn());
        String ref = created.get("booking").get("reference").asText();
        long id = jdbc.queryForObject("SELECT booking_id FROM bookings WHERE booking_reference = ?", Long.class, ref);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action = 'ADMIN_BOOKING_CREATED' AND entity_id = ?
                  AND actor_type = 'USER'""", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_by_user_id <> user_id FROM bookings WHERE booking_id = ?",
                Boolean.class, id)).isTrue();

        // It counts as the customer's one active hold (BR-10).
        mvc.perform(get("/api/bookings/hold").session(customer)).andExpect(jsonPath("$.reference").value(ref));

        mvc.perform(get("/api/admin/bookings/{ref}", ref).session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerEmail").isNotEmpty());
        mvc.perform(get("/api/admin/bookings").session(admin)
                        .param("status", "PENDING_PAYMENT").param("from", "2030-01-07").param("to", "2030-01-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.booking.reference == '%s')]".formatted(ref)).exists());
        mvc.perform(get("/api/admin/bookings").session(admin).param("from", "2030-01-08").param("to", "2030-01-07"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void adminHoldValidatesCustomerAndVehicle() throws Exception {
        newSlot("CAR");
        MockHttpSession admin = adminSession();
        long adminId = userId(ADMIN_EMAIL);
        adminHold(admin, adminId, car, at(3), at(5)).andExpect(status().isNotFound());       // not a customer
        long otherCustomer = newCustomerId();
        adminHold(admin, otherCustomer, car, at(3), at(5)).andExpect(status().isNotFound()); // not their vehicle
        adminHold(admin, customerId, car, at(0), at(2)).andExpect(status().isUnprocessableEntity()); // too soon
    }

    @Test
    void rolesAndCsrfAreEnforced() throws Exception {
        MockHttpSession admin = adminSession();
        mvc.perform(get("/api/bookings").session(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/bookings").session(customer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/bookings").session(customer).contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(car, at(3), at(5))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings").with(csrf()).session(customer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":" + car + "}"))
                .andExpect(status().isBadRequest());
    }

    // ======================================================== lock timeout

    @Test
    void allocationWaitsAtMostFiveSecondsForTheCategoryLock() throws Exception {
        long slot = newSlot("CAR");
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT slot_id FROM parking_slots WHERE slot_id = ? FOR UPDATE")) {
                lock.setLong(1, slot);
                lock.executeQuery();
            }
            long t0 = System.nanoTime();
            createHold(customer, car, at(3), at(5))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("CONCURRENCY_CONFLICT"));
            assertThat(Duration.ofNanos(System.nanoTime() - t0))
                    .isBetween(Duration.ofMillis(4500), Duration.ofSeconds(15));
            other.rollback();
        }
        assertThat(bookingCount(customerId)).isZero();
        createHold(customer, car, at(3), at(5)).andExpect(status().isCreated());
    }

    // ============================================================= helpers

    private static String bookingJson(long vehicle, LocalDateTime start, LocalDateTime end) {
        return "{\"vehicleId\":%d,\"startTime\":\"%s\",\"endTime\":\"%s\"}".formatted(vehicle, start, end);
    }

    private ResultActions quote(long vehicle, LocalDateTime start, LocalDateTime end) throws Exception {
        return mvc.perform(post("/api/bookings/quote").with(csrf()).session(customer)
                .contentType(MediaType.APPLICATION_JSON).content(bookingJson(vehicle, start, end)));
    }

    private ResultActions createHold(MockHttpSession session, long vehicle, LocalDateTime start, LocalDateTime end)
            throws Exception {
        return mvc.perform(post("/api/bookings").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(bookingJson(vehicle, start, end)));
    }

    private ResultActions adminHold(MockHttpSession admin, long customer, long vehicle, LocalDateTime start,
                                    LocalDateTime end) throws Exception {
        return mvc.perform(post("/api/admin/bookings").with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":%d,\"vehicleId\":%d,\"startTime\":\"%s\",\"endTime\":\"%s\"}"
                        .formatted(customer, vehicle, start, end)));
    }

    private ResultActions release(MockHttpSession session, String ref) throws Exception {
        return mvc.perform(post("/api/bookings/{ref}/release-hold", ref).with(csrf()).session(session));
    }

    private String ref(ResultActions result) throws Exception {
        return body(result.andExpect(status().isCreated()).andReturn()).get("reference").asText();
    }

    private JsonNode history(String filter) throws Exception {
        return body(mvc.perform(get("/api/bookings").param("status", filter).session(customer))
                .andExpect(status().isOk()).andReturn()).get("content");
    }

    private static void assertRefs(JsonNode content, String... refs) {
        java.util.List<String> actual = new java.util.ArrayList<>();
        content.forEach(n -> actual.add(n.get("reference").asText()));
        assertThat(actual).containsExactlyInAnyOrder(refs);
    }

    /** A confirmed (or confirmed-then-cancelled) booking for the logged-in customer's car. */
    private String insertOwnBooking(long slot, String status, long startHour, Instant confirmedAt, String reason) {
        String ref = "OWN" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        boolean cancelled = status.equals("CANCELLED");
        jdbc.update("""
                INSERT INTO bookings (booking_reference, user_id, vehicle_id, slot_id, created_by_user_id,
                    scheduled_start, scheduled_end, hold_expires_at, confirmed_at,
                    hourly_rate_snapshot, daily_rate_snapshot, booking_amount, booking_status,
                    cancelled_at, cancellation_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 40, 200, 80, ?, ?, ?)""",
                ref, customerId, car, slot, customerId, ts(instantAt(startHour)), ts(instantAt(startHour + 2)),
                ts(BASE), ts(confirmedAt), status, cancelled ? ts(BASE) : null, reason);
        return ref;
    }

    private String statusOf(String ref) {
        return jdbc.queryForObject("SELECT booking_status FROM bookings WHERE booking_reference = ?", String.class, ref);
    }

    private long systemAudits(String ref) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs a JOIN bookings b ON a.entity_id = b.booking_id
                WHERE b.booking_reference = ? AND a.action = 'HOLD_EXPIRED' AND a.actor_type = 'SYSTEM'
                  AND a.entity_type = 'BOOKING'""", Long.class, ref);
    }

    private long bookingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bookings", Long.class);
    }

    private long bookingCount(long customer) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE user_id = ?", Long.class, customer);
    }
}
