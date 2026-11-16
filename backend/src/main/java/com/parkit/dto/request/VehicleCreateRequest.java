package com.parkit.dto.request;

import com.parkit.domain.enums.VehicleType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** New vehicle (FR-09, FR-10). The registration number is normalized by the service (BR-21). */
public record VehicleCreateRequest(
        @NotBlank @Size(max = 30) String registrationNumber,
        @NotNull VehicleType vehicleType,
        boolean electric,
        @Size(max = 150) String description) {

    /** Only a CAR can be electric (BR-03, ck_electric_car). */
    @AssertTrue(message = "only a CAR can be electric")
    public boolean isElectricConsistent() {
        return !electric || vehicleType == null || vehicleType == VehicleType.CAR;
    }
}
