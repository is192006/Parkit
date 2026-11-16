package com.parkit.repository;

import com.parkit.domain.entity.Booking;
import com.parkit.domain.enums.BookingStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link com.parkit.domain.entity.Booking}. Bookings are addressed by reference in the API. */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(String bookingReference);

    /**
     * True if the vehicle has a current or future active booking (BR-21, FR-12):
     * CHECKED_IN (still inside, including overstay), or not yet ended and either
     * CONFIRMED or an unexpired PENDING_PAYMENT hold (BR-08).
     */
    @Query("""
            select count(b) > 0 from Booking b
            where b.vehicle.id = :vehicleId
              and (b.status = :checkedIn
                   or (b.scheduledEnd > :now
                       and (b.status = :confirmed
                            or (b.status = :pending and b.holdExpiresAt > :now))))
            """)
    boolean existsActiveOrFutureBookingForVehicle(@Param("vehicleId") Long vehicleId,
                                                  @Param("now") Instant now,
                                                  @Param("checkedIn") BookingStatus checkedIn,
                                                  @Param("confirmed") BookingStatus confirmed,
                                                  @Param("pending") BookingStatus pending);

    default boolean hasActiveOrFutureBooking(Long vehicleId, Instant now) {
        return existsActiveOrFutureBookingForVehicle(vehicleId, now,
                BookingStatus.CHECKED_IN, BookingStatus.CONFIRMED, BookingStatus.PENDING_PAYMENT);
    }

    /**
     * True if the slot has a current or future active booking (BR-22): a
     * checked-in vehicle (including an overstay), or a not-yet-ended CONFIRMED
     * booking or unexpired PENDING_PAYMENT hold (BR-08).
     */
    @Query("""
            select count(b) > 0 from Booking b
            where b.slot.id = :slotId
              and (b.status = com.parkit.domain.enums.BookingStatus.CHECKED_IN
                   or (b.scheduledEnd > :now
                       and (b.status = com.parkit.domain.enums.BookingStatus.CONFIRMED
                            or (b.status = com.parkit.domain.enums.BookingStatus.PENDING_PAYMENT
                                and b.holdExpiresAt > :now))))
            """)
    boolean hasActiveOrFutureBookingForSlot(@Param("slotId") Long slotId, @Param("now") Instant now);

    /**
     * True if any booking occupies the slot during {@code [start, end)} under the
     * effective occupancy interval of BR-08 (half-open overlap, BR-07):
     * <ul>
     *   <li>unexpired PENDING_PAYMENT and CONFIRMED: {@code [scheduled_start, scheduled_end)};</li>
     *   <li>CHECKED_IN, not yet past scheduled end:
     *       {@code [min(actual_check_in, scheduled_start), scheduled_end)};</li>
     *   <li>CHECKED_IN and overstaying (scheduled end reached): {@code [actual_check_in, infinity)}.</li>
     * </ul>
     * Used to refuse a slot block that conflicts with a hold, booking or parked vehicle (BR-22).
     */
    @Query("""
            select count(b) > 0 from Booking b
            where b.slot.id = :slotId
              and (
                   ((b.status = com.parkit.domain.enums.BookingStatus.CONFIRMED
                     or (b.status = com.parkit.domain.enums.BookingStatus.PENDING_PAYMENT
                         and b.holdExpiresAt > :now))
                    and b.scheduledStart < :end and b.scheduledEnd > :start)
                or (b.status = com.parkit.domain.enums.BookingStatus.CHECKED_IN
                    and b.scheduledEnd > :now
                    and (b.scheduledStart < :end or b.actualCheckIn < :end)
                    and b.scheduledEnd > :start)
                or (b.status = com.parkit.domain.enums.BookingStatus.CHECKED_IN
                    and b.scheduledEnd <= :now
                    and b.actualCheckIn < :end)
              )
            """)
    boolean existsOccupancyConflictForSlot(@Param("slotId") Long slotId,
                                           @Param("start") Instant start,
                                           @Param("end") Instant end,
                                           @Param("now") Instant now);
}
