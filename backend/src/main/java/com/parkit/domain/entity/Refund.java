package com.parkit.domain.entity;

import com.parkit.domain.enums.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One refund attempt for a payment (table {@code refunds}); append-only (BR-15).
 * Starts PENDING; {@code completed_at} is set exactly when it leaves PENDING.
 */
@Entity
@Table(name = "refunds")
@EntityListeners(AuditingEntityListener.class)
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_id")
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    /** Equals the payment amount (full refunds only, enforced in the service). */
    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    @Digits(integer = 8, fraction = 2)
    @Column(name = "amount", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 20)
    private RefundStatus status = RefundStatus.PENDING;

    @NotBlank
    @Size(max = 255)
    @Column(name = "reason", nullable = false, length = 255, updatable = false)
    private String reason;

    @NotBlank
    @Size(max = 80)
    @Column(name = "idempotency_key", nullable = false, length = 80, unique = true, updatable = false)
    private String idempotencyKey;

    @Size(max = 60)
    @Column(name = "refund_reference", length = 60, unique = true)
    private String refundReference;

    @Size(max = 255)
    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /** Null means initiated by the system. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiated_by_user_id", updatable = false)
    private User initiatedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Refund() {
        // for JPA
    }

    public Refund(Payment payment, BigDecimal amount, String reason, String idempotencyKey, User initiatedBy) {
        this.payment = payment;
        this.amount = amount;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.initiatedBy = initiatedBy;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRefundReference() {
        return refundReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public User getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
