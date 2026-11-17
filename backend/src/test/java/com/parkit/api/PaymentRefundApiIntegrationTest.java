package com.parkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkit.exception.ParkItException;
import com.parkit.gateway.SimulatedOutcome;
import com.parkit.service.PaymentService;
import com.parkit.service.RefundService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 6: simulated payments, confirmation, cancellation and refunds against a
 * real PostgreSQL. The expired-hold test asserts the FAILED attempt and the
 * PAYMENT_EXPIRED status are committed (read through a separate JDBC connection)
 * after the 410 response.
 */
@Import(AbstractBookingIntegrationTest.ClockTestConfig.class)
class PaymentRefundApiIntegrationTest extends AbstractBookingIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    private MockHttpSession customer;
    private long customerId;
    private long car;
    private MockHttpSession admin;
    private long adminId;

    @BeforeEach
    void setUp() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        customer = login(email, PASSWORD);
        customerId = userId(email);
        car = newVehicle(customerId, "CAR");
        admin = adminSession();
        adminId = userId(ADMIN_EMAIL);
        newSlot("CAR");
    }

    // ============================================================ payment

    @Test
    void successfulPaymentConfirmsBookingAndChargesStoredAmount() throws Exception {
        String ref = hold(3, 5);
        // A client-supplied amount is ignored: the DTO has no such field and the stored 80.00 is charged.
        pay(customer, ref, "key-success-1", "{\"simulatedOutcome\":\"SUCCESS\",\"amount\":1.00}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(80.00))
                .andExpect(jsonPath("$.paymentMethod").value("ONLINE_SIMULATED"))
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.transactionReference").isNotEmpty());

        assertThat(bookingStatus(ref)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NOT NULL FROM bookings WHERE booking_reference = ?",
                Boolean.class, ref)).isTrue();
        assertThat(paymentCount(ref, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void bodyIsOptionalAndDefaultsToSuccess() throws Exception {
        String ref = hold(3, 5);
        pay(customer, ref, "key-nobody-01", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void repeatedRequestWithSameKeyReturnsSameAttemptWithoutSecondCharge() throws Exception {
        String ref = hold(3, 5);
        JsonNode first = body(pay(customer, ref, "key-repeat-1", null).andExpect(status().isCreated()).andReturn());
        JsonNode second = body(pay(customer, ref, "key-repeat-1", null).andExpect(status().isOk()).andReturn());
        assertThat(second.get("paymentId").asLong()).isEqualTo(first.get("paymentId").asLong());
        assertThat(second.get("transactionReference").asText()).isEqualTo(first.get("transactionReference").asText());
        assertThat(paymentCount(ref, null)).isEqualTo(1);
    }

    @Test
    void failedPaymentIsRecordedAndHoldStaysOpenForRetryWithNewKey() throws Exception {
        String ref = hold(3, 5);
        pay(customer, ref, "key-fail-0001", "{\"simulatedOutcome\":\"FAILURE\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").isNotEmpty())
                .andExpect(jsonPath("$.bookingStatus").value("PENDING_PAYMENT"));
        assertThat(bookingStatus(ref)).isEqualTo("PENDING_PAYMENT");

        // Replaying the failed key returns the same failure; it does not charge again.
        pay(customer, ref, "key-fail-0001", "{\"simulatedOutcome\":\"SUCCESS\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        pay(customer, ref, "key-retry-001", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(paymentCount(ref, null)).isEqualTo(2);
        assertThat(paymentCount(ref, "SUCCESS")).isEqualTo(1);
        assertThat(bookingStatus(ref)).isEqualTo("CONFIRMED");
    }

    @Test
    void secondPaymentWithDifferentKeyAfterSuccessIsRejected() throws Exception {
        String ref = hold(3, 5);
        pay(customer, ref, "key-first-001", null).andExpect(status().isCreated());
        pay(customer, ref, "key-second-01", null).andExpect(status().isConflict());
        assertThat(paymentCount(ref, null)).isEqualTo(1);
    }

    @Test
    void expiredHoldReturns410AndCommitsFailedAttemptAndExpiredStatus() throws Exception {
        String ref = hold(3, 5);
        clock.advance(Duration.ofMinutes(5)); // hold_expires_at <= now

        pay(customer, ref, "key-expired-1", null)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("HOLD_EXPIRED"));

        // jdbc is a separate connection: these rows are visible only if they were committed.
        assertThat(bookingStatus(ref)).isEqualTo("PAYMENT_EXPIRED");
        List<String> payments = jdbc.queryForList("""
                SELECT payment_status || '/' || failure_reason || '/' || amount FROM payments p
                JOIN bookings b ON b.booking_id = p.booking_id WHERE b.booking_reference = ?""", String.class, ref);
        assertThat(payments).containsExactly("FAILED/HOLD_EXPIRED/80.00");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action = 'HOLD_EXPIRED' AND actor_type = 'SYSTEM'
                  AND entity_id = (SELECT booking_id FROM bookings WHERE booking_reference = ?)""",
                Long.class, ref)).isEqualTo(1);

        // Replaying the key returns 410 again and adds nothing.
        pay(customer, ref, "key-expired-1", null).andExpect(status().isGone());
        assertThat(paymentCount(ref, null)).isEqualTo(1);
        assertThat(bookingStatus(ref)).isEqualTo("PAYMENT_EXPIRED");
    }

    @Test
    void paymentAfterSchedulerAlreadyExpiredTheHoldStillReturns410() throws Exception {
        String ref = hold(3, 5);
        clock.advance(Duration.ofMinutes(6));
        jdbc.update("UPDATE bookings SET booking_status = 'PAYMENT_EXPIRED' WHERE booking_reference = ?", ref);

        pay(customer, ref, "key-swept-001", null).andExpect(status().isGone());
        assertThat(paymentCount(ref, "FAILED")).isEqualTo(1);
        assertThat(bookingStatus(ref)).isEqualTo("PAYMENT_EXPIRED");
    }

    @Test
    void idempotencyKeyIsRequiredAndValidated() throws Exception {
        String ref = hold(3, 5);
        mvc.perform(post("/api/bookings/{ref}/payments", ref).with(csrf()).session(customer))
                .andExpect(status().isBadRequest());
        pay(customer, ref, "bad key with spaces", null).andExpect(status().isBadRequest());
        pay(customer, ref, "x".repeat(41), null).andExpect(status().isBadRequest());
        assertThat(paymentCount(ref, null)).isZero();
    }

    @Test
    void sameKeyOnDifferentBookingsIsIndependent() throws Exception {
        String first = hold(3, 5);
        pay(customer, first, "shared-key-01", null).andExpect(status().isCreated());
        long vehicle2 = newVehicle(customerId, "CAR");
        String second = body(createHold(customer, vehicle2, at(6), at(8)).andExpect(status().isCreated()).andReturn())
                .get("reference").asText();
        pay(customer, second, "shared-key-01", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void customerCannotPayAnotherCustomersOrAnAdminCreatedHold() throws Exception {
        String ref = hold(3, 5);
        MockHttpSession other = newCustomerSession();
        pay(other, ref, "key-other-001", null).andExpect(status().isNotFound());

        String email2 = uniqueEmail();
        register(email2, PASSWORD);
        MockHttpSession customer2 = login(email2, PASSWORD);
        long vehicle2 = newVehicle(userId(email2), "CAR");
        String adminRef = body(adminHold(userId(email2), vehicle2, at(6), at(8)).andExpect(status().isCreated())
                .andReturn()).get("booking").get("reference").asText();
        pay(customer2, adminRef, "key-admin-001", null).andExpect(status().isForbidden());
        assertThat(paymentCount(adminRef, null)).isZero();
    }

    // ===================================================== counter payment

    @Test
    void adminCounterPaymentConfirmsAnyUnexpiredHoldAndIsAudited() throws Exception {
        long vehicle2 = newVehicle(customerId, "CAR");
        String adminRef = body(adminHold(customerId, vehicle2, at(6), at(8)).andExpect(status().isCreated())
                .andReturn()).get("booking").get("reference").asText();
        counter(adminRef, "key-counter-1", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.paymentMethod").value("COUNTER_SIMULATED"))
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"));
        assertThat(jdbc.queryForObject("""
                SELECT recorded_by_user_id FROM payments p JOIN bookings b ON b.booking_id = p.booking_id
                WHERE b.booking_reference = ?""", Long.class, adminRef)).isEqualTo(adminId);
        assertThat(auditCount("COUNTER_PAYMENT_RECORDED", adminRef)).isEqualTo(1);

        counter(adminRef, "key-counter-1", null).andExpect(status().isOk());   // replay
        assertThat(auditCount("COUNTER_PAYMENT_RECORDED", adminRef)).isEqualTo(1);

        // Also works for a hold the customer created.
        String customerRef = hold(10, 12);
        counter(customerRef, "key-counter-2", null).andExpect(status().isCreated());
    }

    @Test
    void counterPaymentOnExpiredHoldReturns410AndCommitsRecords() throws Exception {
        String ref = hold(3, 5);
        clock.advance(Duration.ofMinutes(5));
        counter(ref, "key-counter-3", null)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("HOLD_EXPIRED"));
        assertThat(bookingStatus(ref)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(paymentCount(ref, "FAILED")).isEqualTo(1);
    }

    @Test
    void customerCannotUseAdminEndpoints() throws Exception {
        String ref = hold(3, 5);
        mvc.perform(post("/api/admin/bookings/{ref}/counter-payment", ref).with(csrf()).session(customer)
                        .header("Idempotency-Key", "key-forbid-01"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/bookings/{ref}/cancel", ref).with(csrf()).session(customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/refunds/1/retry").with(csrf()).session(customer))
                .andExpect(status().isForbidden());
    }

    // ============================================== customer cancellation

    @Test
    void customerCancelsAtLeastTwoHoursBeforeStartAndIsRefundedInFull() throws Exception {
        String ref = confirmed(3, 5);
        clock.set(instantAt(1)); // exactly 2 h before the start: still allowed
        cancel(customer, ref, "{\"reason\":\"plans changed\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("CANCELLED"))
                .andExpect(jsonPath("$.booking.cancellationReason").value("plans changed"))
                .andExpect(jsonPath("$.refund.status").value("SUCCESS"))
                .andExpect(jsonPath("$.refund.amount").value(80.00))
                .andExpect(jsonPath("$.refund.refundReference").isNotEmpty());

        assertThat(bookingStatus(ref)).isEqualTo("CANCELLED");
        assertThat(refundCount(ref, null)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT r.idempotency_key = 'refund:' || p.payment_id || ':cancel' FROM refunds r
                JOIN payments p ON p.payment_id = r.payment_id JOIN bookings b ON b.booking_id = p.booking_id
                WHERE b.booking_reference = ?""", Boolean.class, ref)).isTrue();
        assertThat(auditCount("BOOKING_CANCELLED", ref)).isEqualTo(1);

        // Repeating the cancellation is harmless: same answer, no second refund, no second audit.
        cancel(customer, ref, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.status").value("SUCCESS"));
        assertThat(refundCount(ref, null)).isEqualTo(1);
        assertThat(auditCount("BOOKING_CANCELLED", ref)).isEqualTo(1);

        mvc.perform(get("/api/bookings/{ref}/refunds", ref).session(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"));
    }

    @Test
    void customerCancellationOneSecondInsideTheCutoffIsRefused() throws Exception {
        String ref = confirmed(3, 5);
        clock.set(instantAt(1).plusSeconds(1));
        cancel(customer, ref, null).andExpect(status().isUnprocessableEntity());
        assertThat(bookingStatus(ref)).isEqualTo("CONFIRMED");
        assertThat(refundCount(ref, null)).isZero();
    }

    @Test
    void customerCancelDefaultsReasonAndRejectsHoldsAndOtherCustomers() throws Exception {
        String ref = confirmed(3, 5);
        cancel(newCustomerSession(), ref, null).andExpect(status().isNotFound());
        cancel(customer, ref, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.cancellationReason").value("CUSTOMER_REQUESTED"));

        String holdRef = hold(10, 12);
        cancel(customer, holdRef, null).andExpect(status().isConflict()); // release the hold instead
        assertThat(bookingStatus(holdRef)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void confirmedBookingWithoutSuccessfulPaymentIsNotCancelledAndReturns409() throws Exception {
        long slot = newSlot("CAR");
        // Inconsistent data inserted directly: CONFIRMED, but no payment row.
        long bookingId = insertBooking(slot, "CONFIRMED", instantAt(10), instantAt(12), BASE, null, null);
        String ref = jdbc.queryForObject("SELECT booking_reference FROM bookings WHERE booking_id = ?",
                String.class, bookingId);
        jdbc.update("UPDATE bookings SET user_id = ?, vehicle_id = ? WHERE booking_id = ?",
                customerId, car, bookingId);

        cancel(customer, ref, null).andExpect(status().isConflict());
        adminCancel(ref, "{\"reason\":\"test\"}").andExpect(status().isConflict());

        assertThat(bookingStatus(ref)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT cancelled_at IS NULL FROM bookings WHERE booking_id = ?",
                Boolean.class, bookingId)).isTrue();
        assertThat(refundCount(ref, null)).isZero();
        assertThat(auditCount("BOOKING_CANCELLED", ref)).isZero();
    }

    // ================================================== admin cancellation

    @Test
    void adminCancelRequiresReason() throws Exception {
        String ref = confirmed(3, 5);
        adminCancel(ref, "{}").andExpect(status().isBadRequest());
        adminCancel(ref, "{\"reason\":\"  \"}").andExpect(status().isBadRequest());
        assertThat(bookingStatus(ref)).isEqualTo("CONFIRMED");
    }

    @Test
    void adminCancelsAtAnyTimeAndPaidBookingIsRefunded() throws Exception {
        String ref = confirmed(3, 5);
        clock.set(instantAt(2).plus(Duration.ofMinutes(30))); // inside the customer cutoff
        adminCancel(ref, "{\"reason\":\"facility closed\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.booking.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.status").value("SUCCESS"));
        assertThat(auditCount("BOOKING_CANCELLED", ref)).isEqualTo(1);
    }

    @Test
    void adminCancelOfUnpaidHoldNeedsNoRefund() throws Exception {
        String ref = hold(3, 5);
        adminCancel(ref, "{\"reason\":\"customer called\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.booking.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund").doesNotExist());
        assertThat(refundCount(ref, null)).isZero();
        // A released/cancelled hold is not "confirmed", so cancelling it again is an error, not a replay.
        adminCancel(ref, "{\"reason\":\"again\"}").andExpect(status().isConflict());
    }

    @Test
    void failedRefundKeepsBookingCancelledAndCanBeRetriedOnce() throws Exception {
        String ref = confirmed(3, 5);
        adminCancel(ref, "{\"reason\":\"test\",\"simulatedRefundOutcome\":\"FAILURE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.booking.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.status").value("FAILED"))
                .andExpect(jsonPath("$.refund.failureReason").isNotEmpty());
        long failedId = jdbc.queryForObject("""
                SELECT refund_id FROM refunds r JOIN payments p ON p.payment_id = r.payment_id
                JOIN bookings b ON b.booking_id = p.booking_id WHERE b.booking_reference = ?""", Long.class, ref);

        // A failed refund can be retried and fail again; that retry is itself retryable.
        JsonNode second = body(retry(failedId, "{\"simulatedRefundOutcome\":\"FAILURE\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("FAILED")).andReturn());
        // Repeating the retry of the same failed refund returns the same retry, not a new refund.
        retry(failedId, "{\"simulatedRefundOutcome\":\"SUCCESS\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId").value(second.get("refundId").asLong()));
        assertThat(refundCount(ref, null)).isEqualTo(2);

        JsonNode third = body(retry(second.get("refundId").asLong(), null)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCESS")).andReturn());
        assertThat(third.get("amount").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(refundCount(ref, "SUCCESS")).isEqualTo(1);
        assertThat(liveRefunds(ref)).isEqualTo(1);

        // Nothing left to retry: a SUCCESS refund, or an old FAILED one once refunded, is refused.
        retry(third.get("refundId").asLong(), null).andExpect(status().isConflict());
        retry(second.get("refundId").asLong(), null).andExpect(status().isOk()); // replay of the earlier retry
        assertThat(refundCount(ref, null)).isEqualTo(3);
        assertThat(auditCount("REFUND_RETRIED", null)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void retryOfUnknownRefundIs404() throws Exception {
        retry(987654321L, null).andExpect(status().isNotFound());
    }

    // ============================================================ ownership

    @Test
    void paymentAndRefundListsAreOwnerOnly() throws Exception {
        String ref = confirmed(3, 5);
        mvc.perform(get("/api/bookings/{ref}/payments", ref).session(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"));
        MockHttpSession other = newCustomerSession();
        mvc.perform(get("/api/bookings/{ref}/payments", ref).session(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/bookings/{ref}/refunds", ref).session(other)).andExpect(status().isNotFound());
    }

    // ========================================================= concurrency

    @Test
    void concurrentRequestsWithSameKeyChargeOnce() throws Exception {
        String ref = hold(3, 5);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> paymentService.payOnline(customerId, ref, "key-race-same", null));
        }
        List<Object> results = race(tasks);
        Set<Long> ids = new HashSet<>();
        long created = 0;
        for (Object r : results) {
            PaymentService.PaymentAttempt attempt = (PaymentService.PaymentAttempt) r;
            ids.add(attempt.payment().paymentId());
            if (!attempt.replayed()) {
                created++;
            }
        }
        assertThat(ids).hasSize(1);
        assertThat(created).isEqualTo(1);
        assertThat(paymentCount(ref, null)).isEqualTo(1);
        assertThat(paymentCount(ref, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithDifferentKeysProduceOneSuccessfulCharge() throws Exception {
        String ref = hold(3, 5);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String key = "key-race-diff-" + i;
            tasks.add(() -> {
                try {
                    return paymentService.payOnline(customerId, ref, key, null);
                } catch (ParkItException e) {
                    return e;
                }
            });
        }
        List<Object> results = race(tasks);
        long successes = results.stream().filter(r -> r instanceof PaymentService.PaymentAttempt).count();
        assertThat(successes).isEqualTo(1);
        assertThat(results.stream().filter(r -> r instanceof ParkItException)).hasSize(7);
        assertThat(paymentCount(ref, "SUCCESS")).isEqualTo(1);
        assertThat(paymentCount(ref, null)).isEqualTo(1);
        assertThat(bookingStatus(ref)).isEqualTo("CONFIRMED");
    }

    @Test
    void concurrentCancellationsRefundOnce() throws Exception {
        String ref = confirmed(3, 5);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> cancel(customer, ref, null).andReturn().getResponse().getStatus());
        }
        List<Object> results = race(tasks);
        assertThat(results).containsOnly(200);
        assertThat(refundCount(ref, null)).isEqualTo(1);
        assertThat(liveRefunds(ref)).isEqualTo(1);
        assertThat(auditCount("BOOKING_CANCELLED", ref)).isEqualTo(1);
    }

    @Test
    void concurrentRetriesOfOneFailedRefundCreateOneLiveRefund() throws Exception {
        String ref = confirmed(3, 5);
        adminCancel(ref, "{\"reason\":\"test\",\"simulatedRefundOutcome\":\"FAILURE\"}").andExpect(status().isOk());
        long failedId = jdbc.queryForObject("""
                SELECT refund_id FROM refunds r JOIN payments p ON p.payment_id = r.payment_id
                JOIN bookings b ON b.booking_id = p.booking_id WHERE b.booking_reference = ?""", Long.class, ref);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> refundService.retry(adminId, failedId, SimulatedOutcome.SUCCESS));
        }
        List<Object> results = race(tasks);
        Set<Long> ids = new HashSet<>();
        for (Object r : results) {
            ids.add(((RefundService.RetryResult) r).refund().refundId());
        }
        assertThat(ids).hasSize(1);
        assertThat(refundCount(ref, null)).isEqualTo(2);
        assertThat(liveRefunds(ref)).isEqualTo(1);
    }

    // ================================================================ lock

    @Test
    void lockTimeoutRemainsFiveSeconds() {
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
    }

    // ============================================================= helpers

    private String hold(long startHour, long endHour) throws Exception {
        return body(createHold(customer, car, at(startHour), at(endHour)).andExpect(status().isCreated()).andReturn())
                .get("reference").asText();
    }

    private String confirmed(long startHour, long endHour) throws Exception {
        String ref = hold(startHour, endHour);
        pay(customer, ref, "key-confirm-" + ref.substring(Math.max(0, ref.length() - 12)), null)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCESS"));
        return ref;
    }

    private ResultActions createHold(MockHttpSession session, long vehicle, LocalDateTime start, LocalDateTime end)
            throws Exception {
        return mvc.perform(post("/api/bookings").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicleId\":%d,\"startTime\":\"%s\",\"endTime\":\"%s\"}".formatted(vehicle, start, end)));
    }

    private ResultActions adminHold(long customerId, long vehicle, LocalDateTime start, LocalDateTime end)
            throws Exception {
        return mvc.perform(post("/api/admin/bookings").with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":%d,\"vehicleId\":%d,\"startTime\":\"%s\",\"endTime\":\"%s\"}"
                        .formatted(customerId, vehicle, start, end)));
    }

    private ResultActions pay(MockHttpSession session, String ref, String key, String bodyJson) throws Exception {
        var request = post("/api/bookings/{ref}/payments", ref).with(csrf()).session(session)
                .header("Idempotency-Key", key);
        if (bodyJson != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(bodyJson);
        }
        return mvc.perform(request);
    }

    private ResultActions counter(String ref, String key, String bodyJson) throws Exception {
        var request = post("/api/admin/bookings/{ref}/counter-payment", ref).with(csrf()).session(admin)
                .header("Idempotency-Key", key);
        if (bodyJson != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(bodyJson);
        }
        return mvc.perform(request);
    }

    private ResultActions cancel(MockHttpSession session, String ref, String bodyJson) throws Exception {
        var request = post("/api/bookings/{ref}/cancel", ref).with(csrf()).session(session);
        if (bodyJson != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(bodyJson);
        }
        return mvc.perform(request);
    }

    private ResultActions adminCancel(String ref, String bodyJson) throws Exception {
        return mvc.perform(post("/api/admin/bookings/{ref}/cancel", ref).with(csrf()).session(admin)
                .contentType(MediaType.APPLICATION_JSON).content(bodyJson));
    }

    private ResultActions retry(long refundId, String bodyJson) throws Exception {
        var request = post("/api/admin/refunds/{id}/retry", refundId).with(csrf()).session(admin);
        if (bodyJson != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(bodyJson);
        }
        return mvc.perform(request);
    }

    private String bookingStatus(String ref) {
        return jdbc.queryForObject("SELECT booking_status FROM bookings WHERE booking_reference = ?", String.class, ref);
    }

    private long paymentCount(String ref, String status) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM payments p JOIN bookings b ON b.booking_id = p.booking_id
                WHERE b.booking_reference = ? AND (?::text IS NULL OR p.payment_status = ?)""",
                Long.class, ref, status, status);
    }

    private long refundCount(String ref, String status) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM refunds r JOIN payments p ON p.payment_id = r.payment_id
                JOIN bookings b ON b.booking_id = p.booking_id
                WHERE b.booking_reference = ? AND (?::text IS NULL OR r.refund_status = ?)""",
                Long.class, ref, status, status);
    }

    private long liveRefunds(String ref) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM refunds r JOIN payments p ON p.payment_id = r.payment_id
                JOIN bookings b ON b.booking_id = p.booking_id
                WHERE b.booking_reference = ? AND r.refund_status IN ('PENDING', 'SUCCESS')""", Long.class, ref);
    }

    /** Audit entries of an action; for a booking reference, only those on that booking. */
    private long auditCount(String action, String ref) {
        if (ref == null) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = ?", Long.class, action);
        }
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_type = 'BOOKING'
                  AND entity_id = (SELECT booking_id FROM bookings WHERE booking_reference = ?)""",
                Long.class, action, ref);
    }

    /** Starts all tasks together on separate threads and returns every result. */
    private static List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
