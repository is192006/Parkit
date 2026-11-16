package com.parkit.dto.response;

import com.parkit.domain.enums.VehicleType;
import com.parkit.domain.policy.PriceBreakdown;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Quote (FR-24): availability yes/no and the price, without allocating a slot
 * or creating a hold. No slot is identified (BR-04, BR-10).
 *
 * @param customerCancellable false if the start is less than 2 hours away, so a
 *                            booking could never be cancelled by the customer (BR-13);
 *                            the UI must warn before payment
 */
public record QuoteResponse(boolean available, VehicleType vehicleType,
                            Instant startTime, Instant endTime,
                            LocalDateTime startTimeLocal, LocalDateTime endTimeLocal,
                            PriceBreakdown price,
                            boolean customerCancellable, LocalDateTime cancellationDeadlineLocal) {
}
