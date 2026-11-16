package com.parkit.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkit.domain.enums.VehicleType;
import com.parkit.domain.policy.BookingInterval;
import com.parkit.service.SlotAllocationService;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * The balanced allocation query (BR-09, DATABASE_DESIGN.md §7) against real
 * PostgreSQL: ranking, the 30-day window, and every exclusion of BR-08.
 */
@Import(AbstractBookingIntegrationTest.ClockTestConfig.class)
class SlotAllocationIntegrationTest extends AbstractBookingIntegrationTest {

    @Autowired
    private SlotAllocationService allocation;

    private final BookingInterval interval = new BookingInterval(instantAt(4), instantAt(6));

    private List<Long> rank() {
        return allocation.rankAvailableSlots(VehicleType.CAR, interval, BASE);
    }

    @Test
    void fewestRecentConfirmationsWinsThenSortOrder() {
        long a = newSlot("CAR");
        long b = newSlot("CAR");
        long c = newSlot("CAR");
        addConfirmedHistory(a, BASE.minus(1, ChronoUnit.DAYS));
        addConfirmedHistory(a, BASE.minus(2, ChronoUnit.DAYS));
        addConfirmedHistory(b, BASE.minus(3, ChronoUnit.DAYS));
        // c has no history -> first; b (1) -> second; a (2) -> last.
        assertThat(rank()).containsExactly(c, b, a);

        addConfirmedHistory(c, BASE.minus(1, ChronoUnit.HOURS));
        // b and c tie at 1: lower sort_order (b, created first) wins.
        assertThat(rank()).containsExactly(b, c, a);
    }

    @Test
    void onlyConfirmationsWithinThePastThirtyDaysCount() {
        long a = newSlot("CAR");
        long b = newSlot("CAR");
        addConfirmedHistory(a, BASE.minus(31, ChronoUnit.DAYS));   // too old
        addConfirmedHistory(a, BASE.minus(30, ChronoUnit.DAYS));   // boundary: counted
        // Never-confirmed hold on b: confirmed_at null, not counted.
        insertBooking(b, "PAYMENT_EXPIRED", instantAt(30), instantAt(31), null, null, BASE);
        assertThat(rank()).containsExactly(b, a);
    }

    @Test
    void conflictsBlocksInactiveAndOtherCategoriesAreExcluded() {
        long free = newSlot("CAR");
        long confirmed = newSlot("CAR");
        long hold = newSlot("CAR");
        long expiredHold = newSlot("CAR");
        long blocked = newSlot("CAR");
        long inactive = newSlot("CAR");
        long bike = newSlot("BIKE");
        long adjacent = newSlot("CAR");

        insertBooking(confirmed, "CONFIRMED", instantAt(5), instantAt(7), BASE, null, null);
        insertBooking(hold, "PENDING_PAYMENT", instantAt(3), instantAt(5), null, null,
                BASE.plus(Duration.ofMinutes(5)));
        insertBooking(expiredHold, "PENDING_PAYMENT", instantAt(3), instantAt(5), null, null, BASE);
        long admin = userId(ADMIN_EMAIL);
        jdbc.update("""
                INSERT INTO slot_blocks (slot_id, start_time, end_time, reason, created_by_user_id)
                VALUES (?, ?, ?, 'x', ?)""", blocked, ts(instantAt(5)), ts(instantAt(8)), admin);
        jdbc.update("UPDATE parking_slots SET operational_status = 'INACTIVE' WHERE slot_id = ?", inactive);
        insertBooking(adjacent, "CONFIRMED", instantAt(6), instantAt(8), BASE, null, null); // [6,8) touches [4,6)

        assertThat(rank()).containsExactlyInAnyOrder(free, expiredHold, adjacent).doesNotContain(bike);
    }

    @Test
    void earlyCheckInAndOverstayOccupyTheSlot() {
        long early = newSlot("CAR");
        long overstay = newSlot("CAR");
        long free = newSlot("CAR");
        // Checked in early at -1, scheduled 6-8: occupies [-1, 8) -> conflicts with [4, 6).
        insertBooking(early, "CHECKED_IN", instantAt(6), instantAt(8), BASE, instantAt(-1), null);
        // Scheduled end passed (now = BASE), still inside: occupies [check-in, infinity).
        insertBooking(overstay, "CHECKED_IN", instantAt(-5), instantAt(-1), BASE.minus(Duration.ofHours(6)),
                instantAt(-5), null);
        assertThat(rank()).containsExactly(free);
    }
}
