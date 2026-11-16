package com.parkit.service;

import com.parkit.config.PolicyProperties;
import com.parkit.domain.entity.Booking;
import com.parkit.domain.entity.ParkingRate;
import com.parkit.domain.entity.ParkingSlot;
import com.parkit.domain.entity.User;
import com.parkit.domain.entity.Vehicle;
import com.parkit.domain.enums.BookingStatus;
import com.parkit.domain.enums.UserRole;
import com.parkit.domain.enums.VehicleType;
import com.parkit.domain.policy.BookingInterval;
import com.parkit.domain.policy.BookingTimePolicy;
import com.parkit.domain.policy.PriceBreakdown;
import com.parkit.domain.policy.PricingPolicy;
import com.parkit.dto.request.AdminBookingRequest;
import com.parkit.dto.request.BookingHistoryFilter;
import com.parkit.dto.request.BookingRequest;
import com.parkit.dto.response.AdminBookingResponse;
import com.parkit.dto.response.BookingResponse;
import com.parkit.dto.response.PageResponse;
import com.parkit.dto.response.QuoteResponse;
import com.parkit.exception.BookingRuleViolationException;
import com.parkit.exception.DuplicateResourceException;
import com.parkit.exception.ResourceNotFoundException;
import com.parkit.repository.BookingRepository;
import com.parkit.repository.ParkingRateRepository;
import com.parkit.repository.ParkingSlotRepository;
import com.parkit.repository.UserRepository;
import com.parkit.repository.VehicleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quote, payment hold, hold release and booking queries (FR-22 to FR-31,
 * BR-04 to BR-10, BR-20, BR-21, BR-23).
 *
 * <p>Hold creation follows ARCHITECTURE.md §6 in ONE transaction:
 * <ol>
 *   <li>validate the interval (BookingTimePolicy) and the vehicle;</li>
 *   <li>lock all ACTIVE slots of the vehicle's category, ascending slot_id
 *       ({@code FOR UPDATE}, at most 5 s lock_timeout);</li>
 *   <li>mark stale holds of those slots, the vehicle and the customer as PAYMENT_EXPIRED;</li>
 *   <li>check: no active hold for the customer, no overlapping active booking for the vehicle;</li>
 *   <li>balanced allocation, take the first slot;</li>
 *   <li>insert the PENDING_PAYMENT booking (reference, rate snapshot, price, hold = now + 5 min).</li>
 * </ol>
 * The exclusion constraints and the one-hold unique index are the database
 * safety net; their violations become HTTP 409.
 *
 * <p>Ownership: a customer only ever sees their own vehicles and bookings;
 * anything else is reported as not found (FR-06, BR-29).
 */
@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final ParkingSlotRepository slotRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final ParkingRateRepository rateRepository;
    private final FacilityService facilityService;
    private final SlotAllocationService allocationService;
    private final BookingTimePolicy timePolicy;
    private final PricingPolicy pricingPolicy;
    private final BookingReferenceGenerator referenceGenerator;
    private final BookingMapper mapper;
    private final AuditService auditService;
    private final PolicyProperties policy;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository, ParkingSlotRepository slotRepository,
                          VehicleRepository vehicleRepository, UserRepository userRepository,
                          ParkingRateRepository rateRepository, FacilityService facilityService,
                          SlotAllocationService allocationService, BookingTimePolicy timePolicy,
                          PricingPolicy pricingPolicy, BookingReferenceGenerator referenceGenerator,
                          BookingMapper mapper, AuditService auditService, PolicyProperties policy, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
        this.vehicleRepository = vehicleRepository;
        this.userRepository = userRepository;
        this.rateRepository = rateRepository;
        this.facilityService = facilityService;
        this.allocationService = allocationService;
        this.timePolicy = timePolicy;
        this.pricingPolicy = pricingPolicy;
        this.referenceGenerator = referenceGenerator;
        this.mapper = mapper;
        this.auditService = auditService;
        this.policy = policy;
        this.clock = clock;
    }

    // ================================================================ quote

    /**
     * Availability and price without allocating a slot or creating a hold
     * (FR-24, BR-10). Same validation as a hold, so a quote never promises
     * something the hold would refuse for rule reasons. No locks, no writes.
     */
    @Transactional(readOnly = true)
    public QuoteResponse quote(Long customerId, BookingRequest request) {
        Instant now = Instant.now(clock);
        ZoneId zone = facilityService.getZoneId();
        Vehicle vehicle = loadOwnedActiveVehicle(customerId, request.vehicleId());
        BookingInterval interval = validateInterval(request.startTime(), request.endTime(), now, zone);
        requireNoVehicleOverlap(vehicle, interval, now);

        PriceBreakdown price = price(vehicle.getVehicleType(), interval);
        boolean available = allocationService.isAnyAvailable(vehicle.getVehicleType(), interval, now);
        Instant deadline = interval.start().minus(policy.customerCancellationCutoff());
        return new QuoteResponse(available, vehicle.getVehicleType(), interval.start(), interval.end(),
                request.startTime(), request.endTime(), price,
                !now.isAfter(deadline), BookingMapper.local(deadline, zone));
    }

    // ================================================================ holds

    /** Customer starts payment: allocate a slot and create a 5-minute hold (FR-25, BR-10). */
    @Transactional
    public BookingResponse createHold(Long customerId, BookingRequest request) {
        User customer = userRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
        Vehicle vehicle = loadOwnedActiveVehicle(customerId, request.vehicleId());
        Booking booking = placeHold(customer, vehicle, request.startTime(), request.endTime(), customer);
        return mapper.toResponse(booking, facilityService.getZoneId(), Instant.now(clock));
    }

    /**
     * Admin creates a hold for an existing active customer and one of their
     * vehicles (FR-31, BR-23). Identical rules; counter payment follows in a
     * later phase. Audited.
     */
    @Transactional
    public AdminBookingResponse createHoldForCustomer(Long adminId, AdminBookingRequest request) {
        User customer = userRepository.findByIdAndRole(request.customerId(), UserRole.CUSTOMER)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found."));
        if (!customer.isActive()) {
            throw new BookingRuleViolationException("The customer account is deactivated.");
        }
        Vehicle vehicle = loadOwnedActiveVehicle(customer.getId(), request.vehicleId());
        User admin = userRepository.getReferenceById(adminId);
        Booking booking = placeHold(customer, vehicle, request.startTime(), request.endTime(), admin);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", booking.getBookingReference());
        details.put("customerId", customer.getId());
        details.put("vehicleId", vehicle.getId());
        details.put("slotId", booking.getSlot().getId());
        details.put("scheduledStart", booking.getScheduledStart().toString());
        details.put("scheduledEnd", booking.getScheduledEnd().toString());
        details.put("amount", booking.getBookingAmount().toPlainString());
        auditService.recordUserAction(adminId, AuditService.ADMIN_BOOKING_CREATED, AuditService.ENTITY_BOOKING,
                booking.getId(), details);
        return mapper.toAdminResponse(booking, facilityService.getZoneId(), Instant.now(clock));
    }

    /**
     * The customer releases their own unexpired hold: CANCELLED with reason
     * HOLD_RELEASED, no refund (FR-28, BR-10). The booking row is locked first.
     */
    @Transactional
    public BookingResponse releaseHold(Long customerId, String reference) {
        Booking booking = bookingRepository.findByReferenceForUpdate(reference)
                .filter(b -> b.getCustomer().getId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found."));
        Instant now = Instant.now(clock);
        booking.releaseHold(now, userRepository.getReferenceById(customerId));
        Booking saved = bookingRepository.saveAndFlush(booking);
        return mapper.toResponse(saved, facilityService.getZoneId(), now);
    }

    /** The customer's current unexpired hold, if any (FR-30 "payment in progress"). */
    @Transactional(readOnly = true)
    public Optional<BookingResponse> currentHold(Long customerId) {
        Instant now = Instant.now(clock);
        ZoneId zone = facilityService.getZoneId();
        return bookingRepository.findActiveHold(customerId, now).map(b -> mapper.toResponse(b, zone, now));
    }

    // ============================================================== queries

    /** Customer history by filter, paginated (FR-30). See {@link BookingHistoryFilter}. */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> history(Long customerId, BookingHistoryFilter filter, Pageable pageable) {
        Instant now = Instant.now(clock);
        ZoneId zone = facilityService.getZoneId();
        return PageResponse.from(bookingRepository.findHistory(customerId, filter.statuses(),
                filter.confirmedOnly(), filter.unconfirmedOnly(), pageable), b -> mapper.toResponse(b, zone, now));
    }

    /** One of the customer's own bookings by reference; others are "not found" (BR-12, BR-29). */
    @Transactional(readOnly = true)
    public BookingResponse getOwn(Long customerId, String reference) {
        Booking booking = bookingRepository.findByBookingReference(reference)
                .filter(b -> b.getCustomer().getId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found."));
        return mapper.toResponse(booking, facilityService.getZoneId(), Instant.now(clock));
    }

    /** Admin verification by reference (FR-48). */
    @Transactional(readOnly = true)
    public AdminBookingResponse adminGet(String reference) {
        Booking booking = bookingRepository.findByBookingReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found."));
        return mapper.toAdminResponse(booking, facilityService.getZoneId(), Instant.now(clock));
    }

    /**
     * Admin search by status and facility-local date range of the scheduled
     * start ({@code from} and {@code to} inclusive), paginated (FR-55).
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminBookingResponse> adminSearch(BookingStatus status, LocalDate from, LocalDate to,
                                                          Pageable pageable) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BookingRuleViolationException("'to' must not be before 'from'.");
        }
        ZoneId zone = facilityService.getZoneId();
        Instant now = Instant.now(clock);
        Specification<Booking> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            Instant start = from.atStartOfDay(zone).toInstant();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("scheduledStart"), start));
        }
        if (to != null) {
            Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("scheduledStart"), end));
        }
        return PageResponse.from(bookingRepository.findAll(spec, pageable),
                b -> mapper.toAdminResponse(b, zone, now));
    }

    // ============================================================ internals

    /** Steps 1-6 of the hold transaction (class comment). Runs in the caller's transaction. */
    private Booking placeHold(User customer, Vehicle vehicle, LocalDateTime startLocal, LocalDateTime endLocal,
                              User createdBy) {
        Instant now = Instant.now(clock);
        BookingInterval interval = validateInterval(startLocal, endLocal, now, facilityService.getZoneId());
        VehicleType category = vehicle.getVehicleType();

        // Step 2: serialize allocations in this category.
        Map<Long, ParkingSlot> locked = slotRepository.lockActiveByCategory(category).stream()
                .collect(Collectors.toMap(ParkingSlot::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        // Step 3: stale holds would still trip the constraints (they cannot use now()).
        expireStaleHolds(locked.keySet(), vehicle.getId(), customer.getId(), now);

        // Step 4.
        if (bookingRepository.findActiveHold(customer.getId(), now).isPresent()) {
            throw new DuplicateResourceException(
                    "An active payment hold already exists. Complete or release it before starting another.");
        }
        requireNoVehicleOverlap(vehicle, interval, now);

        // Step 5.
        ParkingSlot slot = allocationService.allocate(category, interval, now, locked);

        // Step 6: price from the current rate, snapshotted on the booking (BR-20, FR-34).
        ParkingRate rate = currentRate(category);
        PriceBreakdown price = pricingPolicy.price(interval.duration(), rate.getHourlyRate(), rate.getDailyRate());
        Booking booking = new Booking(referenceGenerator.next(), customer, vehicle, slot, createdBy,
                interval.start(), interval.end(), now.plus(policy.paymentHold()),
                price.hourlyRate(), price.dailyRate(), price.total());
        return bookingRepository.saveAndFlush(booking);
    }

    /** Marks stale holds PAYMENT_EXPIRED (audited as SYSTEM) and flushes before any insert. */
    private void expireStaleHolds(java.util.Collection<Long> slotIds, Long vehicleId, Long customerId, Instant now) {
        // An empty IN list is invalid SQL; -1 matches no slot.
        List<Long> ids = slotIds.isEmpty() ? List.of(-1L) : List.copyOf(slotIds);
        List<Booking> stale = bookingRepository.findStaleHolds(ids, vehicleId, customerId, now);
        for (Booking hold : stale) {
            hold.expireHold(now);
            auditService.recordSystemAction(AuditService.HOLD_EXPIRED, AuditService.ENTITY_BOOKING, hold.getId(),
                    Map.of("reference", hold.getBookingReference(), "trigger", "ALLOCATION"));
        }
        if (!stale.isEmpty()) {
            // Hibernate runs inserts before updates at flush; flush now so the
            // one-hold index and exclusion constraints see the expiry first.
            bookingRepository.flush();
        }
    }

    private BookingInterval validateInterval(LocalDateTime startLocal, LocalDateTime endLocal, Instant now,
                                             ZoneId zone) {
        return timePolicy.validate(startLocal, endLocal, now, zone, facilityService.getOperatingHours());
    }

    private void requireNoVehicleOverlap(Vehicle vehicle, BookingInterval interval, Instant now) {
        if (bookingRepository.existsActiveOverlapForVehicle(vehicle.getId(), interval.start(), interval.end(), now)) {
            throw new DuplicateResourceException("This vehicle already has a booking that overlaps the selected time.");
        }
    }

    private Vehicle loadOwnedActiveVehicle(Long ownerId, Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findByIdAndOwnerId(vehicleId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found."));
        if (!vehicle.isActive()) {
            throw new BookingRuleViolationException("A deactivated vehicle cannot be booked.");
        }
        return vehicle;
    }

    private PriceBreakdown price(VehicleType category, BookingInterval interval) {
        ParkingRate rate = currentRate(category);
        return pricingPolicy.price(interval.duration(), rate.getHourlyRate(), rate.getDailyRate());
    }

    private ParkingRate currentRate(VehicleType category) {
        // Rates are seeded by migration V3; a missing row is a deployment fault (-> 500).
        return rateRepository.findByVehicleType(category)
                .orElseThrow(() -> new IllegalStateException("No rate configured for " + category));
    }
}
