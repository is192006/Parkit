package com.parkit.dto.response;

/**
 * Result of a customer cancellation. {@code refund} is the refund of the paid
 * booking; it is null only when nothing had been paid (a cancelled hold).
 */
public record CancellationResponse(BookingResponse booking, RefundResponse refund) {
}
