package com.parkit.dto.response;

import com.parkit.domain.entity.Refund;
import com.parkit.domain.enums.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** One refund attempt (BR-15). Must be built inside a transaction. */
public record RefundResponse(Long refundId, Long paymentId, String bookingReference, BigDecimal amount,
                             RefundStatus status, String reason, String refundReference, String failureReason,
                             Instant createdAt, Instant completedAt) {

    public static RefundResponse from(Refund r) {
        return new RefundResponse(r.getId(), r.getPayment().getId(),
                r.getPayment().getBooking().getBookingReference(), r.getAmount(), r.getStatus(), r.getReason(),
                r.getRefundReference(), r.getFailureReason(), r.getCreatedAt(), r.getCompletedAt());
    }
}
