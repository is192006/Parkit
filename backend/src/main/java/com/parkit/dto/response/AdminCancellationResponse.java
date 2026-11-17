package com.parkit.dto.response;

/** Result of an admin cancellation; {@code refund} is null when nothing had been paid. A FAILED refund can be retried. */
public record AdminCancellationResponse(AdminBookingResponse booking, RefundResponse refund) {
}
