package com.parkit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.parkit.domain.entity.Booking;
import com.parkit.domain.entity.ParkingRate;
import com.parkit.domain.entity.ParkingSlot;
import com.parkit.domain.entity.User;
import com.parkit.domain.entity.Vehicle;
import com.parkit.domain.enums.VehicleType;
import com.parkit.domain.policy.BookingTimePolicy;
import com.parkit.domain.policy.OperatingHoursPolicy;
import com.parkit.domain.policy.StandardPricingPolicy;
import com.parkit.dto.request.BookingRequest;
import com.parkit.exception.BookingRuleViolationException;
import com.parkit.exception.DuplicateResourceException;
import com.parkit.exception.ResourceNotFoundException;
import com.parkit.repository.BookingRepository;
import com.parkit.repository.ParkingRateRepository;
import com.parkit.repository.ParkingSlotRepository;
import com.parkit.repository.UserRepository;
import com.parkit.repository.VehicleRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/** Order of the hold-creation steps (ARCHITECTURE.md §6) with mocked repositories and a fixed clock. */
class BookingServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.of(2030, 1, 7, 10, 0);
    private static final Instant NOW = NOW_LOCAL.atZone(IST).toInstant();
    private static final long CUSTOMER = 5L;
    private static final long VEHICLE = 9L;

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final ParkingSlotRepository slots = mock(ParkingSlotRepository.class);
    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ParkingRateRepository rates = mock(ParkingRateRepository.class);
    private final FacilityService facility = mock(FacilityService.class);
    private final SlotAllocationService allocation = mock(SlotAllocationService.class);
    private final AuditService audit = mock(AuditService.class);
    private final BookingMapper mapper = mock(BookingMapper.class);
    private final BookingService service = new BookingService(bookings, slots, vehicles, users, rates, facility,
            allocation, new BookingTimePolicy(com.parkit.support.TestPolicies.version1()),
            new StandardPricingPolicy(), new BookingReferenceGenerator(), mapper, audit,
            com.parkit.support.TestPolicies.version1(), Clock.fixed(NOW, ZoneOffset.UTC));

    private Vehicle vehicle;
    private ParkingSlot slot;
    private final BookingRequest request = new BookingRequest(VEHICLE, NOW_LOCAL.plusHours(3), NOW_LOCAL.plusHours(5));

    @BeforeEach
    void setUp() {
        User customer = mock(User.class);
        when(customer.getId()).thenReturn(CUSTOMER);
        vehicle = new Vehicle(customer, "KA01AB1234", VehicleType.CAR, false, null);
        ReflectionTestUtils.setField(vehicle, "id", VEHICLE);
        slot = new ParkingSlot("A-1", 1, VehicleType.CAR);
        ReflectionTestUtils.setField(slot, "id", 1L);
        when(users.findById(CUSTOMER)).thenReturn(Optional.of(customer));
        when(vehicles.findByIdAndOwnerId(VEHICLE, CUSTOMER)).thenReturn(Optional.of(vehicle));
        when(facility.getZoneId()).thenReturn(IST);
        when(facility.getOperatingHours()).thenReturn(OperatingHoursPolicy.always());
        when(slots.lockActiveByCategory(VehicleType.CAR)).thenReturn(List.of(slot));
        when(bookings.findStaleHolds(any(), anyLong(), anyLong(), eq(NOW))).thenReturn(List.of());
        when(bookings.findActiveHold(CUSTOMER, NOW)).thenReturn(Optional.empty());
        when(allocation.allocate(eq(VehicleType.CAR), any(), eq(NOW), anyMap())).thenReturn(slot);
        when(rates.findByVehicleType(VehicleType.CAR)).thenReturn(Optional.of(
                new ParkingRate(VehicleType.CAR, new BigDecimal("40.00"), new BigDecimal("200.00"), null)));
        when(bookings.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void lockComesBeforeEveryAvailabilityCheckAndInsertIsLast() {
        service.createHold(CUSTOMER, request);
        InOrder order = inOrder(slots, bookings, allocation);
        order.verify(slots).lockActiveByCategory(VehicleType.CAR);
        order.verify(bookings).findStaleHolds(any(), eq(VEHICLE), eq(CUSTOMER), eq(NOW));
        order.verify(bookings).findActiveHold(CUSTOMER, NOW);
        order.verify(bookings).existsActiveOverlapForVehicle(eq(VEHICLE), any(), any(), eq(NOW));
        order.verify(allocation).allocate(eq(VehicleType.CAR), any(), eq(NOW), anyMap());
        order.verify(bookings).saveAndFlush(any(Booking.class));
    }

    @Test
    void invalidTimeIsRejectedBeforeAnyLock() {
        BookingRequest tooSoon = new BookingRequest(VEHICLE, NOW_LOCAL.plusMinutes(15), NOW_LOCAL.plusHours(2));
        assertThatThrownBy(() -> service.createHold(CUSTOMER, tooSoon))
                .isInstanceOf(BookingRuleViolationException.class);
        verifyNoInteractions(slots, allocation);
    }

    @Test
    void existingActiveHoldStopsBeforeAllocation() {
        when(bookings.findActiveHold(CUSTOMER, NOW)).thenReturn(Optional.of(mock(Booking.class)));
        assertThatThrownBy(() -> service.createHold(CUSTOMER, request)).isInstanceOf(DuplicateResourceException.class);
        verifyNoInteractions(allocation);
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void staleHoldsAreExpiredAuditedAndFlushedBeforeChecks() {
        Booking stale = mock(Booking.class);
        when(stale.getId()).thenReturn(77L);
        when(stale.getBookingReference()).thenReturn("OLD");
        when(bookings.findStaleHolds(any(), anyLong(), anyLong(), eq(NOW))).thenReturn(List.of(stale));
        service.createHold(CUSTOMER, request);
        InOrder order = inOrder(stale, audit, bookings);
        order.verify(stale).expireHold(NOW);
        order.verify(audit).recordSystemAction(eq(AuditService.HOLD_EXPIRED), eq(AuditService.ENTITY_BOOKING),
                eq(77L), anyMap());
        order.verify(bookings).flush();
        order.verify(bookings).findActiveHold(CUSTOMER, NOW);
    }

    @Test
    void unknownOrInactiveVehicleIsRefused() {
        when(vehicles.findByIdAndOwnerId(VEHICLE, CUSTOMER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createHold(CUSTOMER, request)).isInstanceOf(ResourceNotFoundException.class);
        when(vehicles.findByIdAndOwnerId(VEHICLE, CUSTOMER)).thenReturn(Optional.of(vehicle));
        vehicle.setActive(false);
        assertThatThrownBy(() -> service.createHold(CUSTOMER, request))
                .isInstanceOf(BookingRuleViolationException.class);
        verifyNoInteractions(slots);
    }
}
