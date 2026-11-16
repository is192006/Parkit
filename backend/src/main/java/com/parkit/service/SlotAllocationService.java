package com.parkit.service;

import com.parkit.domain.entity.ParkingSlot;
import com.parkit.domain.enums.VehicleType;
import com.parkit.domain.policy.BookingInterval;
import com.parkit.exception.SlotUnavailableException;
import com.parkit.repository.BookingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Balanced slot allocation (BR-09). The ranking (fewest bookings confirmed in
 * the last 30 days, then lowest sort_order) is done by one native query
 * (DATABASE_DESIGN.md §7); this class only chooses from its result.
 *
 * <p>{@link #allocate} must run inside the caller's transaction after the
 * category's slots are locked, so the answer cannot change before commit.
 * {@link #isAnyAvailable} takes no locks and is for quotes only (FR-24).
 */
@Service
public class SlotAllocationService {

    /** Look-back window for the balancing count (BR-09 step 3). */
    static final Duration BALANCING_WINDOW = Duration.ofDays(30);

    private final BookingRepository bookingRepository;

    public SlotAllocationService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    /** Candidate slot ids, best first. */
    @Transactional(readOnly = true)
    public List<Long> rankAvailableSlots(VehicleType category, BookingInterval interval, Instant now) {
        return bookingRepository.findAllocatableSlotIds(category.name(), interval.start(), interval.end(),
                now, now.minus(BALANCING_WINDOW));
    }

    /** Quote availability: at least one compatible slot free for the whole interval. No lock, no write. */
    @Transactional(readOnly = true)
    public boolean isAnyAvailable(VehicleType category, BookingInterval interval, Instant now) {
        return !rankAvailableSlots(category, interval, now).isEmpty();
    }

    /**
     * Chooses the best slot among the slots the caller has locked.
     *
     * @param lockedSlots the category's ACTIVE slots, locked FOR UPDATE, by id
     * @throws SlotUnavailableException if no compatible slot is free
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ParkingSlot allocate(VehicleType category, BookingInterval interval, Instant now,
                                Map<Long, ParkingSlot> lockedSlots) {
        return rankAvailableSlots(category, interval, now).stream()
                .map(lockedSlots::get)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new SlotUnavailableException(
                        "No parking slot is available for the selected vehicle and time."));
    }
}
