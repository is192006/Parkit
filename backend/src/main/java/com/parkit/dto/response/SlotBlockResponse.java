package com.parkit.dto.response;

import com.parkit.domain.entity.SlotBlock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Slot block with both UTC instants and facility-local times (ARCHITECTURE.md §8). */
public record SlotBlockResponse(Long id, Long slotId, String slotNumber,
                                Instant startTime, Instant endTime,
                                LocalDateTime startTimeLocal, LocalDateTime endTimeLocal,
                                String reason, boolean active, Instant createdAt, Instant cancelledAt) {

    public static SlotBlockResponse from(SlotBlock b, ZoneId zone) {
        return new SlotBlockResponse(b.getId(), b.getSlot().getId(), b.getSlot().getSlotNumber(),
                b.getStartTime(), b.getEndTime(),
                LocalDateTime.ofInstant(b.getStartTime(), zone), LocalDateTime.ofInstant(b.getEndTime(), zone),
                b.getReason(), b.isActive(), b.getCreatedAt(), b.getCancelledAt());
    }
}
