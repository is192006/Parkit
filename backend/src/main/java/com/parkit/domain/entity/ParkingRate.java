package com.parkit.domain.entity;

import com.parkit.domain.enums.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Current hourly and daily rate for one category (table {@code parking_rates}, BR-20). */
@Entity
@Table(name = "parking_rates")
@EntityListeners(AuditingEntityListener.class)
public class ParkingRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rate_id")
    private Long id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 10, unique = true, updatable = false)
    private VehicleType vehicleType;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    @Digits(integer = 8, fraction = 2)
    @Column(name = "hourly_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal hourlyRate;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    @Digits(integer = 8, fraction = 2)
    @Column(name = "daily_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyRate;

    /** Null means seeded by migration. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id")
    private User updatedBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ParkingRate() {
        // for JPA
    }

    public ParkingRate(VehicleType vehicleType, BigDecimal hourlyRate, BigDecimal dailyRate, User updatedBy) {
        this.vehicleType = vehicleType;
        this.hourlyRate = hourlyRate;
        this.dailyRate = dailyRate;
        this.updatedBy = updatedBy;
    }

    /** Replaces both rates; existing bookings keep their snapshots (BR-20). */
    public void updateRates(BigDecimal hourlyRate, BigDecimal dailyRate, User updatedBy) {
        this.hourlyRate = hourlyRate;
        this.dailyRate = dailyRate;
        this.updatedBy = updatedBy;
    }

    public Long getId() {
        return id;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public BigDecimal getHourlyRate() {
        return hourlyRate;
    }

    public BigDecimal getDailyRate() {
        return dailyRate;
    }

    public User getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
