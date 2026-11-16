package com.parkit.service;

import com.parkit.config.PolicyProperties;
import com.parkit.domain.entity.Booking;
import com.parkit.domain.enums.BookingStatus;
import com.parkit.domain.policy.PricingPolicy;
import com.parkit.dto.response.AdminBookingResponse;
import com.parkit.dto.response.BookingRecordType;
import com.parkit.dto.response.BookingResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Maps bookings to response DTOs. Must be called inside a transaction
 * (associations are lazy). The price breakdown is recomputed from the
 * booking's rate snapshot, so it always matches the stored amount (BR-20).
 */
@Component
public class BookingMapper {

    private final PricingPolicy pricingPolicy;
    private final PolicyProperties policy;

    public BookingMapper(PricingPolicy pricingPolicy, PolicyProperties policy) {
        this.pricingPolicy = pricingPolicy;
        this.policy = policy;
    }

    public BookingResponse toResponse(Booking b, ZoneId zone, Instant now) {
        Instant deadline = b.getScheduledStart().minus(policy.customerCancellationCutoff());
        boolean cancellable = (b.getStatus() == BookingStatus.CONFIRMED
                || b.getStatus() == BookingStatus.PENDING_PAYMENT) && !now.isAfter(deadline);
        return new BookingResponse(
                b.getBookingReference(), b.getStatus(), BookingRecordType.of(b),
                b.getVehicle().getId(), b.getVehicle().getRegistrationNumber(), b.getVehicle().getVehicleType(),
                b.getSlot().getSlotNumber(),
                b.getScheduledStart(), b.getScheduledEnd(),
                local(b.getScheduledStart(), zone), local(b.getScheduledEnd(), zone),
                b.getStatus() == BookingStatus.PENDING_PAYMENT ? b.getHoldExpiresAt() : null,
                pricingPolicy.price(Duration.between(b.getScheduledStart(), b.getScheduledEnd()),
                        b.getHourlyRateSnapshot(), b.getDailyRateSnapshot()),
                b.getConfirmedAt(), b.getCancelledAt(), b.getCancellationReason(),
                cancellable, local(deadline, zone), b.getCreatedAt());
    }

    public AdminBookingResponse toAdminResponse(Booking b, ZoneId zone, Instant now) {
        return new AdminBookingResponse(toResponse(b, zone, now), b.getCustomer().getId(),
                b.getCustomer().getName(), b.getCustomer().getEmail(), b.getCreatedBy().getId());
    }

    static LocalDateTime local(Instant instant, ZoneId zone) {
        return LocalDateTime.ofInstant(instant, zone);
    }
}
