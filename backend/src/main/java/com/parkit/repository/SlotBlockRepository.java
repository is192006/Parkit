package com.parkit.repository;

import com.parkit.domain.entity.SlotBlock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link com.parkit.domain.entity.SlotBlock}. */
public interface SlotBlockRepository extends JpaRepository<SlotBlock, Long> {

    List<SlotBlock> findBySlotIdOrderByStartTimeAsc(Long slotId);

    /** Slot id of a block, without loading the block into the persistence context. */
    @Query("select sb.slot.id from SlotBlock sb where sb.id = :blockId")
    Optional<Long> findSlotIdByBlockId(@Param("blockId") Long blockId);

    /**
     * True if the slot has an active (not cancelled) block overlapping the
     * half-open interval {@code [start, end)} (BR-07). Cancelled blocks never conflict.
     */
    @Query("""
            select count(sb) > 0 from SlotBlock sb
            where sb.slot.id = :slotId
              and sb.cancelledAt is null
              and sb.startTime < :end and sb.endTime > :start
            """)
    boolean existsActiveOverlapping(@Param("slotId") Long slotId,
                                    @Param("start") Instant start,
                                    @Param("end") Instant end);
}
