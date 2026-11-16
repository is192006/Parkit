package com.parkit.dto.request;

import com.parkit.domain.enums.VehicleType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Vehicle update (FR-12). The registration number identifies the vehicle and is not changed here. */
public record VehicleUpdateRequest(
        @NotNull VehicleType vehicleType,
        boolean electric,
        @Size(max = 150) String description) {

    @AssertTrue(message = "only a CAR can be electric")
    public boolean isElectricConsistent() {
        return !electric || vehicleType == null || vehicleType == VehicleType.CAR;
    }
}
