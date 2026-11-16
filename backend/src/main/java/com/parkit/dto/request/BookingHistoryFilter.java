package com.parkit.dto.request;

import com.parkit.domain.enums.BookingStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * Customer booking-history filters (FR-30).
 *
 * <p>A "booking" is a record that was confirmed at least once (confirmed_at set).
 * Payment holds that were never confirmed (PAYMENT_EXPIRED, or CANCELLED with
 * reason HOLD_RELEASED) are <b>abandoned payments</b>, not bookings: they are
 * excluded from ALL and CANCELLED and listed only under ABANDONED. The current
 * unexpired hold is served by {@code GET /api/bookings/hold}.
 */
public enum BookingHistoryFilter {
    /** Every booking that was confirmed: upcoming, active, completed, cancelled, no-show. */
    ALL(EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.CHECKED_IN, BookingStatus.COMPLETED,
            BookingStatus.CANCELLED, BookingStatus.NO_SHOW), true, false),
    UPCOMING(EnumSet.of(BookingStatus.CONFIRMED), true, false),
    ACTIVE(EnumSet.of(BookingStatus.CHECKED_IN), true, false),
    COMPLETED(EnumSet.of(BookingStatus.COMPLETED), true, false),
    /** Confirmed bookings that were later cancelled; never released or expired holds. */
    CANCELLED(EnumSet.of(BookingStatus.CANCELLED), true, false),
    NO_SHOW(EnumSet.of(BookingStatus.NO_SHOW), true, false),
    /** Payment holds that expired or were released without ever being confirmed. */
    ABANDONED(EnumSet.of(BookingStatus.PAYMENT_EXPIRED, BookingStatus.CANCELLED), false, true);

    private final Set<BookingStatus> statuses;
    private final boolean confirmedOnly;
    private final boolean unconfirmedOnly;

    BookingHistoryFilter(Set<BookingStatus> statuses, boolean confirmedOnly, boolean unconfirmedOnly) {
        this.statuses = statuses;
        this.confirmedOnly = confirmedOnly;
        this.unconfirmedOnly = unconfirmedOnly;
    }

    public Set<BookingStatus> statuses() {
        return statuses;
    }

    public boolean confirmedOnly() {
        return confirmedOnly;
    }

    public boolean unconfirmedOnly() {
        return unconfirmedOnly;
    }
}
