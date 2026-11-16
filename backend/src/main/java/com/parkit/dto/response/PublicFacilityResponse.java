package com.parkit.dto.response;

import com.parkit.domain.enums.VehicleType;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * Public facility information (FR-21): name, hours and current rates.
 * Contains no audit or user data, so it is safe to serve without login.
 */
public record PublicFacilityResponse(String facilityName, LocalTime openingTime, LocalTime closingTime,
                                     boolean open24Hours, String timezone, List<Rate> rates) {

    /** Current rate of one category. */
    public record Rate(VehicleType vehicleType, BigDecimal hourlyRate, BigDecimal dailyRate) {
    }
}
