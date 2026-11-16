package com.parkit.service;

import com.parkit.domain.entity.User;
import com.parkit.domain.entity.Vehicle;
import com.parkit.domain.enums.UserRole;
import com.parkit.dto.request.VehicleCreateRequest;
import com.parkit.dto.request.VehicleUpdateRequest;
import com.parkit.dto.response.VehicleResponse;
import com.parkit.exception.BookingRuleViolationException;
import com.parkit.exception.DuplicateResourceException;
import com.parkit.exception.ResourceNotFoundException;
import com.parkit.repository.BookingRepository;
import com.parkit.repository.UserRepository;
import com.parkit.repository.VehicleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vehicle management (FR-09 to FR-12, BR-21). Every customer operation is
 * scoped to the caller; a vehicle owned by someone else is reported as not
 * found, so its existence is not revealed (FR-06, BR-29).
 */
@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final Clock clock;

    public VehicleService(VehicleRepository vehicleRepository, UserRepository userRepository,
                          BookingRepository bookingRepository, Clock clock) {
        this.vehicleRepository = vehicleRepository;
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> listOwn(Long ownerId) {
        return vehicleRepository.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream()
                .map(VehicleResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public VehicleResponse getOwn(Long ownerId, Long vehicleId) {
        return VehicleResponse.from(loadOwned(ownerId, vehicleId));
    }

    /** Registration numbers are normalized and globally unique (FR-11, BR-21). */
    @Transactional
    public VehicleResponse create(Long ownerId, VehicleCreateRequest request) {
        String registration = Vehicle.normalizeRegistration(request.registrationNumber());
        if (registration.isEmpty() || registration.length() > 20 || !registration.matches("[A-Z0-9]+")) {
            throw new BookingRuleViolationException(
                    "Registration number must be 1-20 letters or digits after removing spaces and hyphens.");
        }
        if (vehicleRepository.existsByRegistrationNumber(registration)) {
            throw new DuplicateResourceException("A vehicle with this registration number is already registered.");
        }
        User owner = userRepository.getReferenceById(ownerId);
        Vehicle vehicle = new Vehicle(owner, registration, request.vehicleType(), request.electric(),
                blankToNull(request.description()));
        return VehicleResponse.from(vehicleRepository.saveAndFlush(vehicle));
    }

    /** A category change is refused while the vehicle has current or future active bookings (FR-12). */
    @Transactional
    public VehicleResponse update(Long ownerId, Long vehicleId, VehicleUpdateRequest request) {
        Vehicle vehicle = loadOwned(ownerId, vehicleId);
        if (!vehicle.isActive()) {
            throw new BookingRuleViolationException("A deactivated vehicle cannot be updated.");
        }
        if (vehicle.getVehicleType() != request.vehicleType() && hasActiveBookings(vehicle)) {
            throw new BookingRuleViolationException(
                    "The vehicle category cannot change while the vehicle has active or upcoming bookings.");
        }
        vehicle.setVehicleType(request.vehicleType());
        vehicle.setElectric(request.electric());
        vehicle.setDescription(blankToNull(request.description()));
        return VehicleResponse.from(vehicleRepository.saveAndFlush(vehicle));
    }

    /** Refused while the vehicle has current or future active bookings (FR-12). Idempotent. */
    @Transactional
    public VehicleResponse deactivate(Long ownerId, Long vehicleId) {
        Vehicle vehicle = loadOwned(ownerId, vehicleId);
        if (vehicle.isActive()) {
            if (hasActiveBookings(vehicle)) {
                throw new BookingRuleViolationException(
                        "The vehicle cannot be deactivated while it has active or upcoming bookings.");
            }
            vehicle.setActive(false);
            vehicleRepository.saveAndFlush(vehicle);
        }
        return VehicleResponse.from(vehicle);
    }

    /** Admin view of one customer's vehicles. */
    @Transactional(readOnly = true)
    public List<VehicleResponse> listForCustomer(Long customerId) {
        userRepository.findByIdAndRole(customerId, UserRole.CUSTOMER)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found."));
        return listOwn(customerId);
    }

    private boolean hasActiveBookings(Vehicle vehicle) {
        return bookingRepository.hasActiveOrFutureBooking(vehicle.getId(), Instant.now(clock));
    }

    private Vehicle loadOwned(Long ownerId, Long vehicleId) {
        return vehicleRepository.findByIdAndOwnerId(vehicleId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found."));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
