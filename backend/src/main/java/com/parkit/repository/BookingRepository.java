package com.parkit.repository;

import com.parkit.domain.entity.Booking;
import com.parkit.domain.enums.BookingStatus;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link com.parkit.domain.entity.Booking}. Bookings are addressed by reference in the API. */
public interface BookingRepository extends JpaRepository<Booking, Long>, JpaSpecificationExecutor<Booking> {

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

    // ------------------------------------------------------------ Phase 5

    /** Loads a booking with a row lock (lock order: slots, then bookings; ARCHITECTURE.md §6). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.bookingReference = :reference")
    Optional<Booking> findByReferenceForUpdate(@Param("reference") String reference);

    /** Loads a booking by id with a row lock (used when only a refund id is known). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    /**
     * Balanced allocation (BR-09, DATABASE_DESIGN.md §7): ACTIVE slots of the
     * category with no BR-08 conflict and no active block in {@code [start, end)},
     * ordered by the number of bookings confirmed in {@code [since, now]}
     * (including ones later cancelled, no-show or completed; never-confirmed
     * holds have no confirmed_at and do not count), then by sort_order.
     * The caller takes the first id. All instants come from the injected Clock.
     */
    @Query(nativeQuery = true, value = """
            SELECT ps.slot_id
            FROM parking_slots ps
            LEFT JOIN bookings h
                   ON h.slot_id = ps.slot_id
                  AND h.confirmed_at >= :since AND h.confirmed_at <= :now
            WHERE ps.vehicle_type = :category
              AND ps.operational_status = 'ACTIVE'
              AND NOT EXISTS (
                    SELECT 1 FROM bookings b
                    WHERE b.slot_id = ps.slot_id
                      AND (b.booking_status IN ('CONFIRMED', 'CHECKED_IN')
                           OR (b.booking_status = 'PENDING_PAYMENT' AND b.hold_expires_at > :now))
                      AND b.scheduled_start < :end AND b.scheduled_end > :start)
              AND NOT EXISTS (
                    SELECT 1 FROM bookings o
                    WHERE o.slot_id = ps.slot_id AND o.booking_status = 'CHECKED_IN'
                      AND o.actual_check_in < :end
                      AND (o.scheduled_end > :start OR o.scheduled_end <= :now))
              AND NOT EXISTS (
                    SELECT 1 FROM slot_blocks sb
                    WHERE sb.slot_id = ps.slot_id AND sb.cancelled_at IS NULL
                      AND sb.start_time < :end AND sb.end_time > :start)
            GROUP BY ps.slot_id, ps.sort_order
            ORDER BY COUNT(h.booking_id) ASC, ps.sort_order ASC
            """)
    List<Long> findAllocatableSlotIds(@Param("category") String category,
                                      @Param("start") Instant start,
                                      @Param("end") Instant end,
                                      @Param("now") Instant now,
                                      @Param("since") Instant since);

    /**
     * PENDING_PAYMENT rows whose hold has run out ({@code hold_expires_at <= now})
     * on the given slots, for the vehicle, or for the customer. They must be
     * marked PAYMENT_EXPIRED before inserting a new hold, because the exclusion
     * constraints and the one-hold index cannot use now() (DATABASE_DESIGN.md §6).
     */
    @Query("""
            select b from Booking b
            where b.status = com.parkit.domain.enums.BookingStatus.PENDING_PAYMENT
              and b.holdExpiresAt <= :now
              and (b.slot.id in :slotIds or b.vehicle.id = :vehicleId or b.customer.id = :customerId)
            order by b.id
            """)
    List<Booking> findStaleHolds(@Param("slotIds") Collection<Long> slotIds,
                                 @Param("vehicleId") Long vehicleId,
                                 @Param("customerId") Long customerId,
                                 @Param("now") Instant now);

    /** The customer's unexpired hold, if any (BR-10: at most one). */
    @Query("""
            select b from Booking b
            where b.customer.id = :customerId
              and b.status = com.parkit.domain.enums.BookingStatus.PENDING_PAYMENT
              and b.holdExpiresAt > :now
            """)
    Optional<Booking> findActiveHold(@Param("customerId") Long customerId, @Param("now") Instant now);

    /**
     * True if the vehicle has an active booking (unexpired hold, CONFIRMED or
     * CHECKED_IN) whose scheduled interval overlaps {@code [start, end)} (BR-21).
     */
    @Query("""
            select count(b) > 0 from Booking b
            where b.vehicle.id = :vehicleId
              and (b.status = com.parkit.domain.enums.BookingStatus.CONFIRMED
                   or b.status = com.parkit.domain.enums.BookingStatus.CHECKED_IN
                   or (b.status = com.parkit.domain.enums.BookingStatus.PENDING_PAYMENT
                       and b.holdExpiresAt > :now))
              and b.scheduledStart < :end and b.scheduledEnd > :start
            """)
    boolean existsActiveOverlapForVehicle(@Param("vehicleId") Long vehicleId,
                                          @Param("start") Instant start,
                                          @Param("end") Instant end,
                                          @Param("now") Instant now);

    /**
     * Customer booking history (FR-30). {@code confirmedOnly} keeps only rows
     * with confirmed_at set (real bookings); {@code unconfirmedOnly} keeps only
     * never-confirmed rows (abandoned payment holds). See BookingHistoryFilter.
     */
    @Query(value = """
            select b from Booking b
            where b.customer.id = :customerId
              and b.status in :statuses
              and (:confirmedOnly = false or b.confirmedAt is not null)
              and (:unconfirmedOnly = false or b.confirmedAt is null)
            """, countQuery = """
            select count(b) from Booking b
            where b.customer.id = :customerId
              and b.status in :statuses
              and (:confirmedOnly = false or b.confirmedAt is not null)
              and (:unconfirmedOnly = false or b.confirmedAt is null)
            """)
    Page<Booking> findHistory(@Param("customerId") Long customerId,
                              @Param("statuses") Collection<BookingStatus> statuses,
                              @Param("confirmedOnly") boolean confirmedOnly,
                              @Param("unconfirmedOnly") boolean unconfirmedOnly,
                              Pageable pageable);
}
