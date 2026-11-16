package com.parkit.dto.response;

import com.parkit.domain.entity.ParkingRate;
import com.parkit.domain.enums.VehicleType;
import java.math.BigDecimal;
import java.time.Instant;

/** Current rate for one category (FR-32). */
public record RateResponse(VehicleType vehicleType, BigDecimal hourlyRate, BigDecimal dailyRate,
                           Instant updatedAt) {

    public static RateResponse from(ParkingRate r) {
        return new RateResponse(r.getVehicleType(), r.getHourlyRate(), r.getDailyRate(), r.getUpdatedAt());
    }
}
