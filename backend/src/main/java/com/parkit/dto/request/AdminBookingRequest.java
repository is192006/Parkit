package com.parkit.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** Admin hold for an existing customer and one of their vehicles (FR-31, BR-23). */
public record AdminBookingRequest(
        @NotNull Long customerId,
        @NotNull Long vehicleId,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime) {
}
