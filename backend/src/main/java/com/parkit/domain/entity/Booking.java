package com.parkit.domain.entity;

import com.parkit.domain.enums.BookingStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Parking booking (table {@code bookings}).
 *
 * <p>A new booking always starts as a PENDING_PAYMENT hold (BR-10). Status
 * has no public setter; state-transition behaviour (confirm, check-in, ...)
 * is added with the booking use cases in later phases.
 *
 * <p>The composite FK {@code (vehicle_id, user_id) -> vehicles} guarantees
 * the vehicle belongs to the customer; it is enforced by the database.
 */
@Entity
@Table(name = "bookings")
@AttributeOverride(name = "id", column = @Column(name = "booking_id"))
public class Booking extends BaseEntity {

    @NotBlank
    @Size(max = 32)
    @Column(name = "booking_reference", nullable = false, length = 32, unique = true, updatable = false)
    private String bookingReference;

    /** The customer (deliberate denormalization, kept consistent by the composite FK). */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User customer;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false, updatable = false)
    private Vehicle vehicle;

    /** Current slot; may change on reassignment (BR-26). */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ParkingSlot slot;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false, updatable = false)
    private User createdBy;

    @NotNull
    @Column(name = "scheduled_start", nullable = false, updatable = false)
    private Instant scheduledStart;

    @NotNull
    @Column(name = "scheduled_end", nullable = false, updatable = false)
    private Instant scheduledEnd;

    /** Set once at creation, never extended (BR-10). */
    @NotNull
    @Column(name = "hold_expires_at", nullable = false, updatable = false)
    private Instant holdExpiresAt;

    /** Set once on first confirmation, never cleared (BR-09). */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "actual_check_in")
    private Instant actualCheckIn;

    @Column(name = "actual_check_out")
    private Instant actualCheckOut;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    @Digits(integer = 8, fraction = 2)
    @Column(name = "hourly_rate_snapshot", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal hourlyRateSnapshot;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    @Digits(integer = 8, fraction = 2)
    @Column(name = "daily_rate_snapshot", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal dailyRateSnapshot;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    @Digits(integer = 8, fraction = 2)
    @Column(name = "booking_amount", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal bookingAmount;

    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 8, fraction = 2)
    @Column(name = "overstay_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal overstayAmount = BigDecimal.ZERO.setScale(2);

    /** Open-hours overstay minutes only (BR-19). */
    @PositiveOrZero
    @Column(name = "overstay_minutes", nullable = false)
    private int overstayMinutes;

    @Column(name = "reassignment_wait_until")
    private Instant reassignmentWaitUntil;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "booking_status", nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_user_id")
    private User cancelledBy;

    @Size(max = 255)
    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Booking() {
        // for JPA
    }

    /** Creates a new PENDING_PAYMENT hold with a rate snapshot (BR-10, BR-20). */
    public Booking(String bookingReference, User customer, Vehicle vehicle, ParkingSlot slot, User createdBy,
                   Instant scheduledStart, Instant scheduledEnd, Instant holdExpiresAt,
                   BigDecimal hourlyRateSnapshot, BigDecimal dailyRateSnapshot, BigDecimal bookingAmount) {
        this.bookingReference = bookingReference;
        this.customer = customer;
        this.vehicle = vehicle;
        this.slot = slot;
        this.createdBy = createdBy;
        this.scheduledStart = scheduledStart;
        this.scheduledEnd = scheduledEnd;
        this.holdExpiresAt = holdExpiresAt;
        this.hourlyRateSnapshot = hourlyRateSnapshot;
        this.dailyRateSnapshot = dailyRateSnapshot;
        this.bookingAmount = bookingAmount;
        this.status = BookingStatus.PENDING_PAYMENT;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public User getCustomer() {
        return customer;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public ParkingSlot getSlot() {
        return slot;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public Instant getScheduledStart() {
        return scheduledStart;
    }

    public Instant getScheduledEnd() {
        return scheduledEnd;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getActualCheckIn() {
        return actualCheckIn;
    }

    public Instant getActualCheckOut() {
        return actualCheckOut;
    }

    public BigDecimal getHourlyRateSnapshot() {
        return hourlyRateSnapshot;
    }

    public BigDecimal getDailyRateSnapshot() {
        return dailyRateSnapshot;
    }

    public BigDecimal getBookingAmount() {
        return bookingAmount;
    }

    public BigDecimal getOverstayAmount() {
        return overstayAmount;
    }

    public int getOverstayMinutes() {
        return overstayMinutes;
    }

    public Instant getReassignmentWaitUntil() {
        return reassignmentWaitUntil;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public User getCancelledBy() {
        return cancelledBy;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public long getVersion() {
        return version;
    }
}
