package com.parkit.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/**
 * Facility name and operating hours (FR-20, BR-01, BR-06.5). The timezone is
 * fixed to Asia/Kolkata in Version 1 and is not editable.
 */
public record FacilitySettingsUpdateRequest(
        @NotBlank @Size(max = 150) String facilityName,
        @NotNull LocalTime openingTime,
        @NotNull LocalTime closingTime,
        boolean open24Hours) {

    /** Mirrors ck_hours: equal opening and closing times only when open 24 hours. */
    @AssertTrue(message = "opening and closing time may be equal only when open 24 hours")
    public boolean isHoursConsistent() {
        return open24Hours || openingTime == null || !openingTime.equals(closingTime);
    }
}
