package com.parkit.domain.entity;

import com.parkit.domain.enums.SlotStatus;
import com.parkit.domain.enums.VehicleType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Parking slot (table {@code parking_slots}). Never deleted; deactivated via status (BR-22). */
@Entity
@Table(name = "parking_slots")
@AttributeOverride(name = "id", column = @Column(name = "slot_id"))
public class ParkingSlot extends BaseEntity {

    @NotBlank
    @Size(max = 20)
    @Column(name = "slot_number", nullable = false, length = 20, unique = true)
    private String slotNumber;

    /** Unique allocation tie-break order (BR-09). */
    @NotNull
    @Positive
    @Column(name = "sort_order", nullable = false, unique = true)
    private Integer sortOrder;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 10)
    private VehicleType vehicleType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 20)
    private SlotStatus operationalStatus = SlotStatus.ACTIVE;

    protected ParkingSlot() {
        // for JPA
    }

    public ParkingSlot(String slotNumber, int sortOrder, VehicleType vehicleType) {
        this.slotNumber = slotNumber;
        this.sortOrder = sortOrder;
        this.vehicleType = vehicleType;
    }

    public String getSlotNumber() {
        return slotNumber;
    }

    public void setSlotNumber(String slotNumber) {
        this.slotNumber = slotNumber;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(VehicleType vehicleType) {
        this.vehicleType = vehicleType;
    }

    public SlotStatus getOperationalStatus() {
        return operationalStatus;
    }

    public void setOperationalStatus(SlotStatus operationalStatus) {
        this.operationalStatus = operationalStatus;
    }
}
