package com.parkit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.parkit.domain.entity.ParkingSlot;
import com.parkit.domain.entity.SlotBlock;
import com.parkit.domain.entity.User;
import com.parkit.domain.enums.SlotStatus;
import com.parkit.domain.enums.VehicleType;
import com.parkit.dto.request.SlotBlockCreateRequest;
import com.parkit.dto.request.SlotCreateRequest;
import com.parkit.dto.request.SlotUpdateRequest;
import com.parkit.dto.response.SlotBlockResponse;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests of slot and block rules (BR-22) with a fixed clock and mocked repositories. */
class SlotServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 2026-12-01 10:00 IST. */
    private static final Instant NOW = LocalDateTime.of(2026, 12, 1, 10, 0).atZone(IST).toInstant();
    private static final long ADMIN = 1L;
    private static final long SLOT_ID = 7L;

    private final ParkingSlotRepository slots = mock(ParkingSlotRepository.class);
    private final SlotBlockRepository blocks = mock(SlotBlockRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FacilityService facility = mock(FacilityService.class);
    private final AuditService audit = mock(AuditService.class);
    private final SlotService service = new SlotService(slots, blocks, bookings, users, facility, audit,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private ParkingSlot slot;

    @BeforeEach
    void setUp() {
        slot = new ParkingSlot("A-1", 1, VehicleType.CAR);
        ReflectionTestUtils.setField(slot, "id", SLOT_ID);
        when(facility.getZoneId()).thenReturn(IST);
        when(slots.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.of(slot));
        when(slots.saveAndFlush(any(ParkingSlot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(blocks.saveAndFlush(any(SlotBlock.class))).thenAnswer(inv -> {
            SlotBlock b = inv.getArgument(0);
            ReflectionTestUtils.setField(b, "id", 99L);
            return b;
        });
        when(users.getReferenceById(ADMIN)).thenReturn(mock(User.class));
    }

    // ---------------------------------------------------------------- slots

    @Test
    void createSlotRejectsDuplicateNumberWithoutSaving() {
        when(slots.existsBySlotNumber("A-1")).thenReturn(true);
        assertThatThrownBy(() -> service.createSlot(ADMIN, new SlotCreateRequest(" A-1 ", 5, VehicleType.CAR)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(slots, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void createSlotTrimsNumberAndAudits() {
        service.createSlot(ADMIN, new SlotCreateRequest(" B-2 ", 5, VehicleType.BIKE));
        ArgumentCaptor<ParkingSlot> saved = ArgumentCaptor.forClass(ParkingSlot.class);
        verify(slots).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSlotNumber()).isEqualTo("B-2");
        assertThat(saved.getValue().getOperationalStatus()).isEqualTo(SlotStatus.ACTIVE);
        verify(audit).recordUserAction(eq(ADMIN), eq(AuditService.SLOT_CREATED), eq(AuditService.ENTITY_SLOT),
                any(), anyMap());
    }

    @Test
    void deactivationRefusedWhileSlotHasActiveOrFutureBookings() {
        when(bookings.hasActiveOrFutureBookingForSlot(SLOT_ID, NOW)).thenReturn(true);
        assertThatThrownBy(() -> service.updateSlot(ADMIN, SLOT_ID,
                new SlotUpdateRequest("A-1", 1, VehicleType.CAR, SlotStatus.INACTIVE)))
                .isInstanceOf(BookingRuleViolationException.class)
                .hasMessageContaining("inactive");
        assertThat(slot.getOperationalStatus()).isEqualTo(SlotStatus.ACTIVE);
        verifyNoInteractions(audit);
    }

    @Test
    void categoryChangeRefusedWhileSlotHasActiveOrFutureBookings() {
        when(bookings.hasActiveOrFutureBookingForSlot(SLOT_ID, NOW)).thenReturn(true);
        assertThatThrownBy(() -> service.updateSlot(ADMIN, SLOT_ID,
                new SlotUpdateRequest("A-1", 1, VehicleType.BIKE, SlotStatus.ACTIVE)))
                .isInstanceOf(BookingRuleViolationException.class)
                .hasMessageContaining("category");
    }

    @Test
    void reactivationDoesNotCheckBookings() {
        slot.setOperationalStatus(SlotStatus.INACTIVE);
        service.updateSlot(ADMIN, SLOT_ID, new SlotUpdateRequest("A-1", 1, VehicleType.CAR, SlotStatus.ACTIVE));
        verify(bookings, never()).hasActiveOrFutureBookingForSlot(anyLong(), any());
        verify(audit).recordUserAction(eq(ADMIN), eq(AuditService.SLOT_STATUS_CHANGED), anyString(),
                eq(SLOT_ID), anyMap());
    }

    @Test
    void noOpUpdateIsNotAudited() {
        service.updateSlot(ADMIN, SLOT_ID, new SlotUpdateRequest("A-1", 1, VehicleType.CAR, SlotStatus.ACTIVE));
        verify(slots, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void updateUnknownSlotIsNotFound() {
        when(slots.findByIdForUpdate(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateSlot(ADMIN, 5L,
                new SlotUpdateRequest("A-1", 1, VehicleType.CAR, SlotStatus.ACTIVE)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --------------------------------------------------------------- blocks

    @Test
    void blockEndingAtOrBeforeNowIsRejectedBeforeLocking() {
        SlotBlockCreateRequest request = new SlotBlockCreateRequest(
                LocalDateTime.of(2026, 12, 1, 8, 0), LocalDateTime.of(2026, 12, 1, 10, 0), "past");
        assertThatThrownBy(() -> service.createBlock(ADMIN, SLOT_ID, request))
                .isInstanceOf(BookingRuleViolationException.class)
                .hasMessageContaining("future");
        verify(slots, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void blockStartingInThePastIsAllowedAndConvertedFromFacilityTime() {
        SlotBlockCreateRequest request = new SlotBlockCreateRequest(
                LocalDateTime.of(2026, 12, 1, 9, 0), LocalDateTime.of(2026, 12, 1, 12, 0), " Repairs ");
        SlotBlockResponse response = service.createBlock(ADMIN, SLOT_ID, request);

        assertThat(response.startTime()).isEqualTo(Instant.parse("2026-12-01T03:30:00Z"));
        assertThat(response.endTimeLocal()).isEqualTo(LocalDateTime.of(2026, 12, 1, 12, 0));
        assertThat(response.reason()).isEqualTo("Repairs");
        verify(audit).recordUserAction(eq(ADMIN), eq(AuditService.BLOCK_CREATED),
                eq(AuditService.ENTITY_SLOT_BLOCK), eq(99L), anyMap());
    }

    @Test
    void blockOverlappingActiveBlockIsRefused() {
        when(blocks.existsActiveOverlapping(eq(SLOT_ID), any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.createBlock(ADMIN, SLOT_ID, futureBlock()))
                .isInstanceOf(SlotUnavailableException.class)
                .hasMessageContaining("active block");
        verify(blocks, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void blockConflictingWithBookingIsRefused() {
        when(bookings.existsOccupancyConflictForSlot(eq(SLOT_ID), any(), any(), eq(NOW))).thenReturn(true);
        assertThatThrownBy(() -> service.createBlock(ADMIN, SLOT_ID, futureBlock()))
                .isInstanceOf(SlotUnavailableException.class)
                .hasMessageContaining("booking");
        verify(blocks, never()).saveAndFlush(any());
    }

    @Test
    void blockChecksRunAfterTheSlotLock() {
        when(slots.findByIdForUpdate(SLOT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createBlock(ADMIN, SLOT_ID, futureBlock()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(bookings);
        verify(blocks, never()).existsActiveOverlapping(anyLong(), any(), any());
    }

    @Test
    void cancelBlockLocksSlotThenCancelsAndAudits() {
        SlotBlock block = new SlotBlock(slot, NOW, NOW.plusSeconds(3600), "x", mock(User.class));
        ReflectionTestUtils.setField(block, "id", 99L);
        when(blocks.findSlotIdByBlockId(99L)).thenReturn(Optional.of(SLOT_ID));
        when(blocks.findById(99L)).thenReturn(Optional.of(block));

        SlotBlockResponse response = service.cancelBlock(ADMIN, 99L);

        assertThat(response.active()).isFalse();
        assertThat(block.getCancelledAt()).isEqualTo(NOW);
        verify(slots).findByIdForUpdate(SLOT_ID);
        verify(audit).recordUserAction(eq(ADMIN), eq(AuditService.BLOCK_CANCELLED),
                eq(AuditService.ENTITY_SLOT_BLOCK), eq(99L), any(Map.class));
    }

    @Test
    void cancellingCancelledBlockIsInvalidTransition() {
        SlotBlock block = new SlotBlock(slot, NOW, NOW.plusSeconds(3600), "x", mock(User.class));
        block.cancel(NOW, mock(User.class));
        when(blocks.findSlotIdByBlockId(99L)).thenReturn(Optional.of(SLOT_ID));
        when(blocks.findById(99L)).thenReturn(Optional.of(block));

        assertThatThrownBy(() -> service.cancelBlock(ADMIN, 99L))
                .isInstanceOf(InvalidStateTransitionException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void cancellingUnknownBlockIsNotFound() {
        when(blocks.findSlotIdByBlockId(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelBlock(ADMIN, 5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static SlotBlockCreateRequest futureBlock() {
        return new SlotBlockCreateRequest(LocalDateTime.of(2026, 12, 2, 9, 0),
                LocalDateTime.of(2026, 12, 2, 12, 0), "x");
    }
}
