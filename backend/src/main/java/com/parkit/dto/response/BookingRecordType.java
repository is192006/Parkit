package com.parkit.dto.response;

import com.parkit.domain.entity.Booking;
import com.parkit.domain.enums.BookingStatus;

/**
 * How a booking record should be presented (FR-30), so clients can always
 * tell an abandoned payment hold from a cancelled confirmed booking.
 */
public enum BookingRecordType {
    /** PENDING_PAYMENT: payment in progress. */
    PAYMENT_HOLD,
    /** Never confirmed: PAYMENT_EXPIRED, or CANCELLED with confirmed_at null (e.g. HOLD_RELEASED). */
    ABANDONED_PAYMENT,
    /** Confirmed at least once (confirmed_at set), whatever its current status. */
    BOOKING;

    public static BookingRecordType of(Booking b) {
        if (b.getStatus() == BookingStatus.PENDING_PAYMENT) {
            return PAYMENT_HOLD;
        }
        return b.getConfirmedAt() == null ? ABANDONED_PAYMENT : BOOKING;
    }
}
