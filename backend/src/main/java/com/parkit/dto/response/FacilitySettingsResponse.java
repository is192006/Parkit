package com.parkit.dto.response;

import com.parkit.domain.entity.FacilitySettings;
import java.time.Instant;
import java.time.LocalTime;

/** Facility settings (admin view, FR-20). */
public record FacilitySettingsResponse(String facilityName, LocalTime openingTime, LocalTime closingTime,
                                       boolean open24Hours, String timezone, Instant updatedAt) {

    public static FacilitySettingsResponse from(FacilitySettings f) {
        return new FacilitySettingsResponse(f.getFacilityName(), f.getOpeningTime(), f.getClosingTime(),
                f.isOpen24Hours(), f.getTimezone(), f.getUpdatedAt());
    }
}
