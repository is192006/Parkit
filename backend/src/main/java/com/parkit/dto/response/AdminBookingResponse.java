package com.parkit.dto.response;

/** Admin view of a booking: the booking plus its customer and creator (FR-31, FR-48, FR-55). */
public record AdminBookingResponse(BookingResponse booking, Long customerId, String customerName,
                                   String customerEmail, Long createdByUserId) {
}
