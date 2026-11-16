package com.parkit.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Time-bounded block of a slot (table {@code slot_blocks}, BR-22).
 * Active while {@code cancelled_at} is null; cancelled blocks are kept.
 */
@Entity
@Table(name = "slot_blocks")
@EntityListeners(AuditingEntityListener.class)
public class SlotBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "block_id")
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false, updatable = false)
    private ParkingSlot slot;

    @NotNull
    @Column(name = "start_time", nullable = false, updatable = false)
    private Instant startTime;

    @NotNull
    @Column(name = "end_time", nullable = false, updatable = false)
    private Instant endTime;

    @NotBlank
    @Size(max = 255)
    @Column(name = "reason", nullable = false, length = 255, updatable = false)
    private String reason;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false, updatable = false)
    private User createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_user_id")
    private User cancelledBy;

    protected SlotBlock() {
        // for JPA
    }

    public SlotBlock(ParkingSlot slot, Instant startTime, Instant endTime, String reason, User createdBy) {
        this.slot = slot;
        this.startTime = startTime;
        this.endTime = endTime;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    /** Mirrors ck_block_interval. */
    @AssertTrue(message = "end time must be after start time")
    public boolean isIntervalValid() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }

    public boolean isActive() {
        return cancelledAt == null;
    }

    /**
     * Cancels (unblocks) this block; the record is kept (BR-22).
     * Sets cancelled_at and cancelled_by together (ck_block_cancel).
     *
     * @throws IllegalStateException if the block is already cancelled
     */
    public void cancel(Instant now, User cancelledBy) {
        if (!isActive()) {
            throw new IllegalStateException("Block is already cancelled");
        }
        this.cancelledAt = now;
        this.cancelledBy = cancelledBy;
    }

    public Long getId() {
        return id;
    }

    public ParkingSlot getSlot() {
        return slot;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public String getReason() {
        return reason;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public User getCancelledBy() {
        return cancelledBy;
    }
}
