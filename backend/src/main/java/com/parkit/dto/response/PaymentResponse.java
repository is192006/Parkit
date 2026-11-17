package com.parkit.dto.response;

import com.parkit.domain.entity.Payment;
import com.parkit.domain.enums.BookingStatus;
import com.parkit.domain.enums.PaymentMethod;
import com.parkit.domain.enums.PaymentStatus;
import com.parkit.domain.enums.PaymentType;
import java.math.BigDecimal;
import java.time.Instant;

/** One payment attempt plus the booking's status after it (FR-35). Must be built inside a transaction. */
public record PaymentResponse(Long paymentId, String bookingReference, PaymentType paymentType,
                              PaymentMethod paymentMethod, BigDecimal amount, PaymentStatus status,
                              String transactionReference, String failureReason, BookingStatus bookingStatus,
                              Instant createdAt, Instant completedAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getBooking().getBookingReference(), p.getPaymentType(),
                p.getPaymentMethod(), p.getAmount(), p.getStatus(), p.getTransactionReference(),
                p.getFailureReason(), p.getBooking().getStatus(), p.getCreatedAt(), p.getCompletedAt());
    }
}
