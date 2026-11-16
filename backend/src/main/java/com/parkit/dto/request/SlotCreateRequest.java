package com.parkit.dto.request;

import com.parkit.domain.enums.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** New parking slot (FR-15, FR-16). New slots start ACTIVE. */
public record SlotCreateRequest(
        @NotBlank @Size(max = 20) String slotNumber,
        @NotNull @Positive Integer sortOrder,
        @NotNull VehicleType vehicleType) {
}
