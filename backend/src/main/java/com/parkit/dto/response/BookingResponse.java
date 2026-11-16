package com.parkit.dto.response;

import com.parkit.domain.enums.BookingStatus;
import com.parkit.domain.enums.VehicleType;
import com.parkit.domain.policy.PriceBreakdown;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Booking details for the owning customer. Times are given as UTC instants and
 * facility-local date-times (ARCHITECTURE.md §8).
 *
 * @param recordType          BOOKING, PAYMENT_HOLD or ABANDONED_PAYMENT (FR-30)
 * @param holdExpiresAt       set only while PENDING_PAYMENT
 * @param customerCancellable whether the customer could cancel now (BR-13); a
 *                            pending hold is reported for the warning before payment
 */
public record BookingResponse(String reference, BookingStatus status, BookingRecordType recordType,
                              Long vehicleId, String registrationNumber, VehicleType vehicleType,
                              String slotNumber,
                              Instant startTime, Instant endTime,
                              LocalDateTime startTimeLocal, LocalDateTime endTimeLocal,
                              Instant holdExpiresAt, PriceBreakdown price,
                              Instant confirmedAt, Instant cancelledAt, String cancellationReason,
                              boolean customerCancellable, LocalDateTime cancellationDeadlineLocal,
                              Instant createdAt) {
}
