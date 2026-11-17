package com.parkit.service;

import com.parkit.domain.entity.Booking;
import com.parkit.domain.entity.Payment;
import com.parkit.domain.entity.Refund;
import com.parkit.domain.entity.User;
import com.parkit.domain.enums.RefundStatus;
import com.parkit.dto.response.RefundResponse;
import com.parkit.exception.InvalidStateTransitionException;
import com.parkit.exception.ResourceNotFoundException;
import com.parkit.gateway.GatewayRefundRequest;
import com.parkit.gateway.GatewayResult;
import com.parkit.gateway.PaymentGateway;
import com.parkit.gateway.SimulatedOutcome;
import com.parkit.repository.BookingRepository;
import com.parkit.repository.RefundRepository;
import com.parkit.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full refunds of a successful BOOKING payment and admin retry of failed ones
 * (BR-13 to BR-15, FR-40 to FR-42).
 *
 * <p>A refund row is inserted PENDING and moved to SUCCESS or FAILED inside the
 * same transaction, so PENDING is never visible to other transactions. The
 * partial unique index {@code ux_one_live_refund} (one PENDING/SUCCESS refund
 * per payment) is the database guarantee against double refunds; the booking
 * row lock taken by every caller (lock order: bookings, then payments and
 * refunds) makes the service-level checks race-free.
 *
 * <p>Idempotency keys are generated here, never taken from clients:
 * <ul>
 *   <li>cancellation refund: {@code refund:<paymentId>:cancel};</li>
 *   <li>retry of failed refund N: {@code refund:<paymentId>:retry:<N>}, so
 *       repeating a retry returns the earlier retry instead of refunding again.</li>
 * </ul>
 */
@Service
public class RefundService {

    private static final Set<RefundStatus> LIVE = Set.of(RefundStatus.PENDING, RefundStatus.SUCCESS);
    private static final int REASON_MAX = 255;

    private final RefundRepository refundRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final PaymentGateway gateway;
    private final AuditService auditService;
    private final Clock clock;

    public RefundService(RefundRepository refundRepository, BookingRepository bookingRepository,
                         UserRepository userRepository, PaymentGateway gateway, AuditService auditService,
                         Clock clock) {
        this.refundRepository = refundRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.gateway = gateway;
        this.auditService = auditService;
        this.clock = clock;
    }

    static String cancellationKey(Payment payment) {
        return "refund:" + payment.getId() + ":cancel";
    }

    static String retryKey(Refund failed) {
        return "refund:" + failed.getPayment().getId() + ":retry:" + failed.getId();
    }

    /**
     * Refunds the whole payment inside the caller's transaction. Returns the
     * existing refund when {@code key} was already used (idempotent replay).
     * The result may be FAILED; that is recorded, not thrown.
     *
     * @throws InvalidStateTransitionException if a PENDING or SUCCESS refund already exists
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund issueFullRefund(Payment payment, String reason, String key, User initiatedBy,
                                  SimulatedOutcome outcome) {
        Optional<Refund> existing = refundRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (refundRepository.existsByPaymentIdAndStatusIn(payment.getId(), LIVE)) {
            throw new InvalidStateTransitionException("This payment has already been refunded.");
        }
        Instant now = Instant.now(clock);
        Refund refund = refundRepository.saveAndFlush(
                new Refund(payment, payment.getAmount(), truncate(reason), key, initiatedBy));

        GatewayResult result = gateway.refund(new GatewayRefundRequest(key, payment.getTransactionReference(),
                payment.getAmount(), outcome));
        if (result.success()) {
            refund.markSuccess(result.reference(), now);
        } else {
            refund.markFailed(result.failureReason(), now);
        }
        refundRepository.saveAndFlush(refund);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", payment.getBooking().getBookingReference());
        details.put("paymentId", payment.getId());
        details.put("refundId", refund.getId());
        details.put("amount", refund.getAmount().toPlainString());
        details.put("status", refund.getStatus().name());
        auditService.recordUserAction(initiatedBy.getId(), AuditService.REFUND_ISSUED, AuditService.ENTITY_REFUND,
                refund.getId(), details);
        return refund;
    }

    /** Outcome of a retry; {@code replayed} is true when an earlier retry of the same refund is returned. */
    public record RetryResult(RefundResponse refund, boolean replayed) {
    }

    /**
     * Admin retries a FAILED refund as a new refund record (BR-15). Locks the
     * booking first, so concurrent retries are serialized: the second one finds
     * the first one's refund (same generated key) and returns it unchanged.
     */
    @Transactional
    public RetryResult retry(Long adminId, Long refundId, SimulatedOutcome outcome) {
        Long bookingId = refundRepository.findBookingIdByRefundId(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund not found."));
        Booking locked = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund not found."));
        // Read only after the lock, so the status is current.
        Refund failed = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund not found."));

        Optional<Refund> earlier = refundRepository.findByIdempotencyKey(retryKey(failed));
        if (earlier.isPresent()) {
            return new RetryResult(RefundResponse.from(earlier.get()), true);
        }
        if (failed.getStatus() != RefundStatus.FAILED) {
            throw new InvalidStateTransitionException(
                    "Only a FAILED refund can be retried; this refund is %s.".formatted(failed.getStatus()));
        }
        Refund retried = issueFullRefund(failed.getPayment(), "Retry of refund " + failed.getId() + ": "
                + failed.getReason(), retryKey(failed), userRepository.getReferenceById(adminId),
                SimulatedOutcome.orDefault(outcome));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", locked.getBookingReference());
        details.put("failedRefundId", failed.getId());
        details.put("newRefundId", retried.getId());
        details.put("status", retried.getStatus().name());
        auditService.recordUserAction(adminId, AuditService.REFUND_RETRIED, AuditService.ENTITY_REFUND,
                failed.getId(), details);
        return new RetryResult(RefundResponse.from(retried), false);
    }

    private static String truncate(String reason) {
        return reason.length() <= REASON_MAX ? reason : reason.substring(0, REASON_MAX);
    }
}
