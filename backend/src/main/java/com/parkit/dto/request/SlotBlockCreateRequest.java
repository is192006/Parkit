package com.parkit.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * Time-bounded slot block (FR-18, BR-22). Times are facility-local
 * (Asia/Kolkata, e.g. {@code 2026-12-01T09:30}) and converted to instants by
 * the service (BR-28). "End after now" needs the clock, so it is checked in the service.
 */
public record SlotBlockCreateRequest(
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime,
        @NotBlank @Size(max = 255) String reason) {

    @AssertTrue(message = "end time must be after start time")
    public boolean isIntervalValid() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }
}
