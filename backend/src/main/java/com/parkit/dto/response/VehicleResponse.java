package com.parkit.dto.response;

import com.parkit.domain.entity.Vehicle;
import com.parkit.domain.enums.VehicleType;
import java.time.Instant;

/** Vehicle details. */
public record VehicleResponse(Long id, String registrationNumber, VehicleType vehicleType,
                              boolean electric, String description, boolean active, Instant createdAt) {

    public static VehicleResponse from(Vehicle v) {
        return new VehicleResponse(v.getId(), v.getRegistrationNumber(), v.getVehicleType(),
                v.isElectric(), v.getDescription(), v.isActive(), v.getCreatedAt());
    }
}
