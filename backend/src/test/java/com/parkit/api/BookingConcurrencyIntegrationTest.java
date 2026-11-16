package com.parkit.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkit.dto.request.BookingRequest;
import com.parkit.exception.ParkItException;
import com.parkit.service.BookingService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

/**
 * Real concurrent hold requests against PostgreSQL (ARCHITECTURE.md §6, §11):
 * all requests start together on separate threads and transactions. Exactly
 * one may win; every loser must fail with a 409-type error, never a double booking.
 */
@Import(AbstractBookingIntegrationTest.ClockTestConfig.class)
class BookingConcurrencyIntegrationTest extends AbstractBookingIntegrationTest {

    private static final int THREADS = 10;

    @Autowired
    private BookingService bookingService;

    @Test
    void tenCustomersRaceForTheLastSlot() throws Exception {
        long slot = newSlot("CAR");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long customer = newCustomerId();
            long vehicle = newVehicle(customer, "CAR");
            tasks.add(() -> bookingService.createHold(customer, request(vehicle, at(3), at(5))));
        }
        Outcome outcome = race(tasks);
        assertThat(outcome.successes).isEqualTo(1);
        assertThat(outcome.conflicts).isEqualTo(THREADS - 1);
        assertThat(activeBookingsOnSlot(slot)).isEqualTo(1);
    }

    @Test
    void sameVehicleCannotBeHeldTwice() throws Exception {
        newSlot("CAR");
        newSlot("CAR");
        long customer = newCustomerId();
        long vehicle = newVehicle(customer, "CAR");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            // Overlapping but different intervals: two free slots exist, so only the vehicle/hold rules stop them.
            int shift = i % 2;
            tasks.add(() -> bookingService.createHold(customer, request(vehicle, at(3 + shift), at(5 + shift))));
        }
        Outcome outcome = race(tasks);
        assertThat(outcome.successes).isEqualTo(1);
        assertThat(outcome.conflicts).isEqualTo(THREADS - 1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bookings WHERE vehicle_id = ? AND booking_status = 'PENDING_PAYMENT'""",
                Long.class, vehicle)).isEqualTo(1);
    }

    /**
     * CAR and BIKE allocations lock different slot rows, so they really run in
     * parallel; the one-hold unique index is what stops the second (23505 -> 409).
     */
    @Test
    void oneCustomerCannotGetTwoHoldsAcrossCategories() throws Exception {
        newSlot("CAR");
        newSlot("BIKE");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long customer = newCustomerId();
            long car = newVehicle(customer, "CAR");
            long bike = newVehicle(customer, "BIKE");
            tasks.add(() -> {
                Outcome pair = race(List.of(
                        () -> bookingService.createHold(customer, request(car, at(3), at(5))),
                        () -> bookingService.createHold(customer, request(bike, at(10), at(12)))));
                assertThat(pair.successes).as("holds for one customer").isLessThanOrEqualTo(1);
                // Release the winner's slots for the next pair.
                jdbc.update("UPDATE bookings SET booking_status = 'CANCELLED', cancelled_at = now(), "
                        + "cancellation_reason = 'TEST' WHERE user_id = ? AND booking_status = 'PENDING_PAYMENT'",
                        customer);
                return pair.successes;
            });
        }
        for (Callable<Object> task : tasks) {
            assertThat((Integer) task.call()).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------- helpers

    private static BookingRequest request(long vehicle, LocalDateTime start, LocalDateTime end) {
        return new BookingRequest(vehicle, start, end);
    }

    private long activeBookingsOnSlot(long slot) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM bookings WHERE slot_id = ?
                  AND booking_status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN')""", Long.class, slot);
    }

    private record Outcome(int successes, int conflicts) {
    }

    /** Starts all tasks at the same moment; classifies results. Unexpected errors fail the test. */
    private static Outcome race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            int ok = 0;
            int conflicts = 0;
            for (Future<Object> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    ok++;
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    boolean conflict = (cause instanceof ParkItException p && p.getStatus().value() == 409)
                            || cause instanceof DataAccessException;   // 23505 / 23P01 / lock -> 409 in the API
                    if (!conflict) {
                        throw new AssertionError("Unexpected failure", cause);
                    }
                    conflicts++;
                }
            }
            return new Outcome(ok, conflicts);
        } finally {
            pool.shutdownNow();
        }
    }
}
