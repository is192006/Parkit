package com.parkit.domain.entity;

import com.parkit.domain.enums.VehicleType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** Customer vehicle (table {@code vehicles}). */
@Entity
@Table(name = "vehicles")
@AttributeOverride(name = "id", column = @Column(name = "vehicle_id"))
public class Vehicle extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User owner;

    /** Stored normalized (BR-21): uppercase, no spaces or hyphens. */
    @NotBlank
    @Size(max = 20)
    @Pattern(regexp = "[^\\s\\-a-z]+", message = "must be normalized (uppercase, no spaces or hyphens)")
    @Column(name = "registration_number", nullable = false, length = 20, unique = true)
    private String registrationNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 10)
    private VehicleType vehicleType;

    @Column(name = "is_electric", nullable = false)
    private boolean electric;

    @Size(max = 150)
    @Column(name = "description", length = 150)
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Vehicle() {
        // for JPA
    }

    public Vehicle(User owner, String registrationNumber, VehicleType vehicleType,
                   boolean electric, String description) {
        this.owner = owner;
        this.registrationNumber = normalizeRegistration(registrationNumber);
        this.vehicleType = vehicleType;
        this.electric = electric;
        this.description = description;
    }

    /** BR-21: uppercase, remove spaces and hyphens. */
    public static String normalizeRegistration(String raw) {
        return raw == null ? null : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** Mirrors ck_electric_car: only a CAR may be electric. */
    @AssertTrue(message = "only a CAR can be electric")
    public boolean isElectricConsistent() {
        return !electric || vehicleType == VehicleType.CAR;
    }

    public User getOwner() {
        return owner;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(VehicleType vehicleType) {
        this.vehicleType = vehicleType;
    }

    public boolean isElectric() {
        return electric;
    }

    public void setElectric(boolean electric) {
        this.electric = electric;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
