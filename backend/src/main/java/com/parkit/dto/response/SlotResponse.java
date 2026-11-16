package com.parkit.dto.response;

import com.parkit.domain.entity.ParkingSlot;
import com.parkit.domain.enums.SlotStatus;
import com.parkit.domain.enums.VehicleType;
import java.time.Instant;

/** Parking slot details (admin). */
public record SlotResponse(Long id, String slotNumber, Integer sortOrder, VehicleType vehicleType,
                           SlotStatus operationalStatus, Instant createdAt, Instant updatedAt) {

    public static SlotResponse from(ParkingSlot s) {
        return new SlotResponse(s.getId(), s.getSlotNumber(), s.getSortOrder(), s.getVehicleType(),
                s.getOperationalStatus(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
