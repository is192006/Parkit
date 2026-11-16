package com.parkit.service;

import com.parkit.domain.entity.ParkingSlot;
import com.parkit.domain.entity.SlotBlock;
import com.parkit.domain.enums.SlotStatus;
import com.parkit.dto.request.SlotBlockCreateRequest;
import com.parkit.dto.request.SlotCreateRequest;
import com.parkit.dto.request.SlotUpdateRequest;
import com.parkit.dto.response.PageResponse;
import com.parkit.dto.response.SlotBlockResponse;
import com.parkit.dto.response.SlotResponse;
import com.parkit.exception.BookingRuleViolationException;
import com.parkit.exception.DuplicateResourceException;
import com.parkit.exception.InvalidStateTransitionException;
import com.parkit.exception.ResourceNotFoundException;
import com.parkit.exception.SlotUnavailableException;
import com.parkit.repository.BookingRepository;
import com.parkit.repository.ParkingSlotRepository;
import com.parkit.repository.SlotBlockRepository;
import com.parkit.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slot inventory and time-bounded slot blocks (FR-15 to FR-19, BR-22).
 *
 * <p>Concurrency: every operation that must not race with booking allocation
 * on the same slot (status/category change, block create/cancel) first locks
 * the slot row with {@code SELECT ... FOR UPDATE}. Allocation (later phase)
 * locks the same rows, so a check made here stays true until commit
 * (ARCHITECTURE.md §6, lock order: slots, then bookings).
 */
@Service
public class SlotService {

    private final ParkingSlotRepository slotRepository;
    private final SlotBlockRepository blockRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final FacilityService facilityService;
    private final AuditService auditService;
    private final Clock clock;

    public SlotService(ParkingSlotRepository slotRepository, SlotBlockRepository blockRepository,
                       BookingRepository bookingRepository, UserRepository userRepository,
                       FacilityService facilityService, AuditService auditService, Clock clock) {
        this.slotRepository = slotRepository;
        this.blockRepository = blockRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.facilityService = facilityService;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- slots

    @Transactional(readOnly = true)
    public PageResponse<SlotResponse> listSlots(Pageable pageable) {
        return PageResponse.from(slotRepository.findAll(pageable), SlotResponse::from);
    }

    /** Slot number and sort order are unique (FR-15); new slots are ACTIVE. */
    @Transactional
    public SlotResponse createSlot(Long adminId, SlotCreateRequest request) {
        String slotNumber = request.slotNumber().trim();
        if (slotRepository.existsBySlotNumber(slotNumber)) {
            throw new DuplicateResourceException("A slot with this slot number already exists.");
        }
        if (slotRepository.existsBySortOrder(request.sortOrder())) {
            throw new DuplicateResourceException("A slot with this sort order already exists.");
        }
        ParkingSlot slot = slotRepository.saveAndFlush(
                new ParkingSlot(slotNumber, request.sortOrder(), request.vehicleType()));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("slotNumber", slot.getSlotNumber());
        details.put("sortOrder", slot.getSortOrder());
        details.put("vehicleType", slot.getVehicleType().name());
        details.put("operationalStatus", slot.getOperationalStatus().name());
        auditService.recordUserAction(adminId, AuditService.SLOT_CREATED, AuditService.ENTITY_SLOT,
                slot.getId(), details);
        return SlotResponse.from(slot);
    }

    /**
     * Updates number, sort order, category and status. Making a slot INACTIVE
     * or changing its category is refused while it has future active bookings
     * or a checked-in vehicle (BR-22). A status change is audited as
     * SLOT_STATUS_CHANGED, any other change as SLOT_UPDATED; a no-op is not audited.
     */
    @Transactional
    public SlotResponse updateSlot(Long adminId, Long slotId, SlotUpdateRequest request) {
        ParkingSlot slot = lockSlot(slotId);
        String slotNumber = request.slotNumber().trim();

        if (!slot.getSlotNumber().equals(slotNumber)
                && slotRepository.existsBySlotNumberAndIdNot(slotNumber, slotId)) {
            throw new DuplicateResourceException("A slot with this slot number already exists.");
        }
        if (!slot.getSortOrder().equals(request.sortOrder())
                && slotRepository.existsBySortOrderAndIdNot(request.sortOrder(), slotId)) {
            throw new DuplicateResourceException("A slot with this sort order already exists.");
        }

        boolean categoryChange = slot.getVehicleType() != request.vehicleType();
        boolean deactivation = slot.getOperationalStatus() == SlotStatus.ACTIVE
                && request.operationalStatus() == SlotStatus.INACTIVE;
        if ((categoryChange || deactivation)
                && bookingRepository.hasActiveOrFutureBookingForSlot(slotId, Instant.now(clock))) {
            throw new BookingRuleViolationException(categoryChange
                    ? "The slot category cannot change while the slot has active or upcoming bookings."
                    : "The slot cannot be made inactive while it has active or upcoming bookings.");
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        AuditService.diff(changes, "slotNumber", slot.getSlotNumber(), slotNumber);
        AuditService.diff(changes, "sortOrder", slot.getSortOrder(), request.sortOrder());
        AuditService.diff(changes, "vehicleType", slot.getVehicleType(), request.vehicleType());
        AuditService.diff(changes, "operationalStatus", slot.getOperationalStatus(),
                request.operationalStatus());
        if (changes.isEmpty()) {
            return SlotResponse.from(slot);
        }

        String action = slot.getOperationalStatus() != request.operationalStatus()
                ? AuditService.SLOT_STATUS_CHANGED : AuditService.SLOT_UPDATED;
        slot.setSlotNumber(slotNumber);
        slot.setSortOrder(request.sortOrder());
        slot.setVehicleType(request.vehicleType());
        slot.setOperationalStatus(request.operationalStatus());
        ParkingSlot saved = slotRepository.saveAndFlush(slot);
        auditService.recordUserAction(adminId, action, AuditService.ENTITY_SLOT, slotId, changes);
        return SlotResponse.from(saved);
    }

    // --------------------------------------------------------------- blocks

    /** All blocks of a slot, active and cancelled, oldest start first. */
    @Transactional(readOnly = true)
    public List<SlotBlockResponse> listBlocks(Long slotId) {
        ParkingSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found."));
        ZoneId zone = facilityService.getZoneId();
        return blockRepository.findBySlotIdOrderByStartTimeAsc(slot.getId()).stream()
                .map(b -> SlotBlockResponse.from(b, zone))
                .toList();
    }

    /**
     * Blocks a slot for {@code [start, end)} (FR-18, BR-22). Start may be in
     * the past ("blocked from now"); end must be after now and after start.
     * Refused if the interval overlaps another active block on the slot, or a
     * hold, confirmed booking or checked-in vehicle (BR-08 occupancy); the
     * admin must resolve such conflicts first (e.g. by admin cancellation).
     */
    @Transactional
    public SlotBlockResponse createBlock(Long adminId, Long slotId, SlotBlockCreateRequest request) {
        ZoneId zone = facilityService.getZoneId();
        Instant start = request.startTime().atZone(zone).toInstant();
        Instant end = request.endTime().atZone(zone).toInstant();
        Instant now = Instant.now(clock);
        if (!end.isAfter(start)) {
            throw new BookingRuleViolationException("The block end time must be after its start time.");
        }
        if (!end.isAfter(now)) {
            throw new BookingRuleViolationException("The block end time must be in the future.");
        }

        ParkingSlot slot = lockSlot(slotId);
        if (blockRepository.existsActiveOverlapping(slotId, start, end)) {
            throw new SlotUnavailableException("The slot already has an active block overlapping this period.");
        }
        if (bookingRepository.existsOccupancyConflictForSlot(slotId, start, end, now)) {
            throw new SlotUnavailableException("The slot has a hold, booking or parked vehicle in this period. "
                    + "Resolve it before blocking the slot.");
        }

        SlotBlock block = blockRepository.saveAndFlush(new SlotBlock(slot, start, end,
                request.reason().trim(), userRepository.getReferenceById(adminId)));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("slotId", slotId);
        details.put("slotNumber", slot.getSlotNumber());
        details.put("startTime", start.toString());
        details.put("endTime", end.toString());
        details.put("reason", block.getReason());
        auditService.recordUserAction(adminId, AuditService.BLOCK_CREATED, AuditService.ENTITY_SLOT_BLOCK,
                block.getId(), details);
        return SlotBlockResponse.from(block, zone);
    }

    /** Cancels (unblocks) an active block; the record is kept (BR-22). */
    @Transactional
    public SlotBlockResponse cancelBlock(Long adminId, Long blockId) {
        Long slotId = blockRepository.findSlotIdByBlockId(blockId)
                .orElseThrow(() -> new ResourceNotFoundException("Slot block not found."));
        // Lock order: the slot row first (ARCHITECTURE.md §6), so cancellation is
        // serialized with block creation and allocation on the same slot. The block
        // is read only after the lock, so a concurrent cancel is always seen.
        lockSlot(slotId);
        SlotBlock block = blockRepository.findById(blockId)
                .orElseThrow(() -> new ResourceNotFoundException("Slot block not found."));
        if (!block.isActive()) {
            throw new InvalidStateTransitionException("The slot block is already cancelled.");
        }
        block.cancel(Instant.now(clock), userRepository.getReferenceById(adminId));
        SlotBlock saved = blockRepository.saveAndFlush(block);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("slotId", saved.getSlot().getId());
        details.put("startTime", saved.getStartTime().toString());
        details.put("endTime", saved.getEndTime().toString());
        auditService.recordUserAction(adminId, AuditService.BLOCK_CANCELLED, AuditService.ENTITY_SLOT_BLOCK,
                blockId, details);
        return SlotBlockResponse.from(saved, facilityService.getZoneId());
    }

    private ParkingSlot lockSlot(Long slotId) {
        return slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found."));
    }
}
