package com.parkit.dto.request;

import com.parkit.domain.enums.SlotStatus;
import com.parkit.domain.enums.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Full replacement of a slot's editable fields (FR-15 to FR-17, BR-22). */
public record SlotUpdateRequest(
        @NotBlank @Size(max = 20) String slotNumber,
        @NotNull @Positive Integer sortOrder,
        @NotNull VehicleType vehicleType,
        @NotNull SlotStatus operationalStatus) {
}
