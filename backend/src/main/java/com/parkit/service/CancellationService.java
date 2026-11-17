package com.parkit.service;

import com.parkit.config.PolicyProperties;
import com.parkit.domain.entity.Booking;
import com.parkit.domain.entity.Payment;
import com.parkit.domain.entity.Refund;
import com.parkit.domain.entity.User;
import com.parkit.domain.enums.BookingStatus;
import com.parkit.domain.enums.PaymentStatus;
import com.parkit.domain.enums.PaymentType;
import com.parkit.dto.response.AdminCancellationResponse;
import com.parkit.dto.response.CancellationResponse;
import com.parkit.dto.response.RefundResponse;
import com.parkit.exception.BookingRuleViolationException;
import com.parkit.exception.InvalidStateTransitionException;
import com.parkit.exception.ResourceNotFoundException;
import com.parkit.gateway.SimulatedOutcome;
import com.parkit.repository.BookingRepository;
import com.parkit.repository.PaymentRepository;
import com.parkit.repository.RefundRepository;
import com.parkit.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer and admin cancellation with automatic full refund (BR-13, BR-14, FR-40, FR-41).
 *
 * <p>One transaction: lock the booking row, check the rule, set CANCELLED, create
 * the refund (PENDING, then SUCCESS or FAILED), audit. A FAILED refund does not
 * undo the cancellation; it is stored and an admin can retry it
 * ({@link RefundService#retry}).
 *
 * <p>Cancelling is idempotent: repeating it on a booking that was confirmed and
 * is now CANCELLED returns the current state and never creates another refund.
 */
@Service
public class CancellationService {

    static final String CUSTOMER_DEFAULT_REASON = "CUSTOMER_REQUESTED";

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final UserRepository userRepository;
    private final RefundService refundService;
    private final FacilityService facilityService;
    private final BookingMapper mapper;
    private final AuditService auditService;
    private final PolicyProperties policy;
    private final Clock clock;

    public CancellationService(BookingRepository bookingRepository, PaymentRepository paymentRepository,
                               RefundRepository refundRepository, UserRepository userRepository,
                               RefundService refundService, FacilityService facilityService,
                               BookingMapper mapper, AuditService auditService, PolicyProperties policy,
                               Clock clock) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.userRepository = userRepository;
        this.refundService = refundService;
        this.facilityService = facilityService;
        this.mapper = mapper;
        this.auditService = auditService;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Customer cancels their own CONFIRMED booking up to and including exactly
     * 2 hours before the scheduled start (BR-13); the refund always succeeds.
     */
    @Transactional
    public CancellationResponse customerCancel(Long customerId, String reference, String reason) {
        Booking booking = bookingRepository.findByReferenceForUpdate(reference)
                .filter(b -> b.getCustomer().getId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found."));
        Instant now = Instant.now(clock);

        if (!isReplay(booking)) {
            if (booking.getStatus() != BookingStatus.CONFIRMED) {
                throw new InvalidStateTransitionException(booking.getStatus() == BookingStatus.PENDING_PAYMENT
                        ? "This booking is an unpaid hold; release the hold instead."
                        : "A booking in status %s cannot be cancelled.".formatted(booking.getStatus()));
            }
            Instant deadline = booking.getScheduledStart().minus(policy.customerCancellationCutoff());
            if (now.isAfter(deadline)) { // exactly at the deadline is still allowed
                throw new BookingRuleViolationException(
                        "Bookings can only be cancelled up to 2 hours before the start time.");
            }
            cancelAndRefund(booking, userRepository.getReferenceById(customerId),
                    reason == null || reason.isBlank() ? CUSTOMER_DEFAULT_REASON : reason.strip(),
                    SimulatedOutcome.SUCCESS, now);
        }
        return new CancellationResponse(
                mapper.toResponse(booking, facilityService.getZoneId(), now), latestRefund(booking));
    }

    /** Admin cancels a PENDING_PAYMENT or CONFIRMED booking at any time before check-in (BR-14). */
    @Transactional
    public AdminCancellationResponse adminCancel(Long adminId, String reference, String reason,
                                                 SimulatedOutcome refundOutcome) {
        Booking booking = bookingRepository.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found."));
        Instant now = Instant.now(clock);

        if (!isReplay(booking)) {
            cancelAndRefund(booking, userRepository.getReferenceById(adminId), reason.strip(),
                    SimulatedOutcome.orDefault(refundOutcome), now);
        }
        return new AdminCancellationResponse(
                mapper.toAdminResponse(booking, facilityService.getZoneId(), now), latestRefund(booking));
    }

    // ============================================================ internals

    /** Already cancelled after having been confirmed: a repeated request, not an error. */
    private static boolean isReplay(Booking booking) {
        return booking.getStatus() == BookingStatus.CANCELLED && booking.getConfirmedAt() != null;
    }

    private void cancelAndRefund(Booking booking, User actor, String reason, SimulatedOutcome outcome, Instant now) {
        boolean paid = booking.getStatus() == BookingStatus.CONFIRMED;
        // Inconsistent data (CONFIRMED without a successful payment) is checked BEFORE any change:
        // nothing is cancelled, refunded or audited, and the client gets a controlled 409.
        Payment payment = null;
        if (paid) {
            payment = paymentRepository.findByBookingIdAndPaymentTypeAndStatus(
                            booking.getId(), PaymentType.BOOKING, PaymentStatus.SUCCESS)
                    .orElseThrow(() -> new InvalidStateTransitionException(
                            "This booking is confirmed but has no successful payment on record, so it cannot be "
                                    + "cancelled automatically. Please contact support."));
        }
        booking.cancel(now, actor, reason);          // rejects statuses the state machine does not allow
        bookingRepository.saveAndFlush(booking);

        Refund refund = null;
        if (paid) {
            refund = refundService.issueFullRefund(payment, reason, RefundService.cancellationKey(payment),
                    actor, outcome);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", booking.getBookingReference());
        details.put("reason", reason);
        details.put("refunded", refund != null);
        if (refund != null) {
            details.put("refundId", refund.getId());
            details.put("refundStatus", refund.getStatus().name());
        }
        auditService.recordUserAction(actor.getId(), AuditService.BOOKING_CANCELLED, AuditService.ENTITY_BOOKING,
                booking.getId(), details);
    }

    /** The most recent refund of the booking, or null if none was ever issued. */
    private RefundResponse latestRefund(Booking booking) {
        List<Refund> refunds = refundRepository.findByBookingId(booking.getId());
        return refunds.isEmpty() ? null : RefundResponse.from(refunds.get(refunds.size() - 1));
    }
}
