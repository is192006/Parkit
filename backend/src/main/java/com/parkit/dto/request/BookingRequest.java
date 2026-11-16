package com.parkit.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * Customer quote or hold request (FR-22): a vehicle and facility-local
 * start/end date-times (e.g. {@code 2026-12-01T09:30}, Asia/Kolkata, BR-28).
 * There is no slot field: customers never choose a slot (BR-04).
 */
public record BookingRequest(
        @NotNull Long vehicleId,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime) {
}
