package com.parkit.service;

import com.parkit.domain.entity.Booking;
import com.parkit.domain.entity.Payment;
import com.parkit.domain.entity.User;
import com.parkit.domain.enums.BookingStatus;
import com.parkit.domain.enums.PaymentMethod;
import com.parkit.domain.enums.PaymentStatus;
import com.parkit.domain.enums.PaymentType;
import com.parkit.dto.response.PaymentResponse;
import com.parkit.dto.response.RefundResponse;
import com.parkit.exception.ForbiddenOperationException;
import com.parkit.exception.InvalidRequestException;
import com.parkit.exception.InvalidStateTransitionException;
import com.parkit.exception.ResourceNotFoundException;
import com.parkit.gateway.ChargeRequest;
import com.parkit.gateway.GatewayResult;
import com.parkit.gateway.PaymentGateway;
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
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Online and counter payment of a booking hold (BR-10, BR-11, BR-23, FR-32 to FR-39).
 *
 * <p>Every attempt runs in ONE transaction that starts by locking the booking
 * row ({@code FOR UPDATE}, 5 s lock_timeout). That lock serializes concurrent
 * requests for the same booking, so:
 * <ul>
 *   <li>a repeated or concurrent request with the same Idempotency-Key finds the
 *       first request's committed payment and returns it (no second charge);</li>
 *   <li>two different keys can never both succeed: the second sees CONFIRMED;</li>
 *   <li>the charged amount is read from the locked booking, never from the client;</li>
 *   <li>payment SUCCESS and booking CONFIRMED commit or roll back together.</li>
 * </ul>
 * The partial unique indexes on {@code payments} are the database safety net.
 *
 * <p><b>Expired hold.</b> The FAILED attempt (reason {@code HOLD_EXPIRED}) and the
 * PAYMENT_EXPIRED status are written normally and the method <i>returns</i>
 * {@code holdExpired = true}; it does not throw. The controller turns that into
 * HTTP 410 only after the transaction has committed, so an exception can never
 * roll those records back.
 *
 * <p><b>Idempotency scope.</b> The client key (max 40 chars) is stored prefixed
 * with the payment method and booking id, so keys never collide across
 * bookings or customers and one customer cannot probe another's keys.
 */
@Service
public class PaymentService {

    public static final String REASON_HOLD_EXPIRED = "HOLD_EXPIRED";
    static final int KEY_MAX = 40;
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1," + KEY_MAX + "}");

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final UserRepository userRepository;
    private final PaymentGateway gateway;
    private final AuditService auditService;
    private final Clock clock;

    public PaymentService(BookingRepository bookingRepository, PaymentRepository paymentRepository,
                          RefundRepository refundRepository, UserRepository userRepository,
                          PaymentGateway gateway, AuditService auditService, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.userRepository = userRepository;
        this.gateway = gateway;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * @param payment     the attempt (new or replayed)
     * @param replayed    true when the Idempotency-Key was already used
     * @param holdExpired true when the attempt was rejected because the hold had expired (HTTP 410)
     */
    public record PaymentAttempt(PaymentResponse payment, boolean replayed, boolean holdExpired) {
    }

    /** A customer pays online for a hold they created themselves. */
    @Transactional
    public PaymentAttempt payOnline(Long customerId, String reference, String idempotencyKey,
                                    SimulatedOutcome outcome) {
        validateKey(idempotencyKey);
        Booking booking = bookingRepository.findByReferenceForUpdate(reference)
                .filter(b -> b.getCustomer().getId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found."));
        if (!booking.getCreatedBy().getId().equals(customerId)) {
            throw new ForbiddenOperationException(
                    "This booking was created by staff. Please pay at the counter.");
        }
        return attempt(booking, PaymentMethod.ONLINE_SIMULATED, null,
                storedKey("ON", booking, idempotencyKey), outcome);
    }

    /** An admin records a simulated counter payment for any unexpired hold (BR-23). Audited. */
    @Transactional
    public PaymentAttempt recordCounterPayment(Long adminId, String reference, String idempotencyKey,
                                               SimulatedOutcome outcome) {
        validateKey(idempotencyKey);
        Booking booking = bookingRepository.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found."));
        User admin = userRepository.getReferenceById(adminId);
        PaymentAttempt result = attempt(booking, PaymentMethod.COUNTER_SIMULATED, admin,
                storedKey("CT", booking, idempotencyKey), outcome);
        if (!result.replayed()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reference", booking.getBookingReference());
            details.put("paymentId", result.payment().paymentId());
            details.put("amount", result.payment().amount().toPlainString());
            details.put("status", result.payment().status().name());
            if (result.payment().failureReason() != null) {
                details.put("failureReason", result.payment().failureReason());
            }
            auditService.recordUserAction(adminId, AuditService.COUNTER_PAYMENT_RECORDED,
                    AuditService.ENTITY_BOOKING, booking.getId(), details);
        }
        return result;
    }

    /** The customer's own booking's payment attempts, oldest first. */
    @Transactional(readOnly = true)
    public List<PaymentResponse> listPayments(Long customerId, String reference) {
        Booking booking = ownBooking(customerId, reference);
        return paymentRepository.findByBookingIdOrderByCreatedAtAsc(booking.getId()).stream()
                .map(PaymentResponse::from).toList();
    }

    /** The customer's own booking's refund attempts, oldest first. */
    @Transactional(readOnly = true)
    public List<RefundResponse> listRefunds(Long customerId, String reference) {
        Booking booking = ownBooking(customerId, reference);
        return refundRepository.findByBookingId(booking.getId()).stream().map(RefundResponse::from).toList();
    }

    // ============================================================ internals

    private PaymentAttempt attempt(Booking booking, PaymentMethod method, User recordedBy, String key,
                                   SimulatedOutcome outcome) {
        Instant now = Instant.now(clock);

        // Replay first: it must work even after the booking has since changed state.
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            Payment p = existing.get();
            return new PaymentAttempt(PaymentResponse.from(p), true, REASON_HOLD_EXPIRED.equals(p.getFailureReason()));
        }

        BookingStatus status = booking.getStatus();
        if (status == BookingStatus.CONFIRMED || paymentRepository.findByBookingIdAndPaymentTypeAndStatus(
                booking.getId(), PaymentType.BOOKING, PaymentStatus.SUCCESS).isPresent()) {
            throw new InvalidStateTransitionException("This booking has already been paid.");
        }
        if (status != BookingStatus.PENDING_PAYMENT && status != BookingStatus.PAYMENT_EXPIRED) {
            throw new InvalidStateTransitionException(
                    "A booking in status %s cannot be paid.".formatted(status));
        }

        // The amount is always the stored booking amount (BR-20).
        Payment payment = paymentRepository.saveAndFlush(new Payment(booking, PaymentType.BOOKING, method,
                booking.getBookingAmount(), key, recordedBy));

        if (status == BookingStatus.PAYMENT_EXPIRED || !booking.getHoldExpiresAt().isAfter(now)) {
            payment.markFailed(REASON_HOLD_EXPIRED, now);
            if (status == BookingStatus.PENDING_PAYMENT) {
                booking.expireHold(now);
                bookingRepository.saveAndFlush(booking);
                auditService.recordSystemAction(AuditService.HOLD_EXPIRED, AuditService.ENTITY_BOOKING,
                        booking.getId(), Map.of("reference", booking.getBookingReference(),
                                "trigger", "PAYMENT_ATTEMPT"));
            }
            paymentRepository.saveAndFlush(payment);
            return new PaymentAttempt(PaymentResponse.from(payment), false, true);
        }

        GatewayResult result = gateway.charge(new ChargeRequest(key, booking.getBookingReference(),
                payment.getAmount(), SimulatedOutcome.orDefault(outcome)));
        if (result.success()) {
            payment.markSuccess(result.reference(), now);
            booking.confirm(now);
            bookingRepository.saveAndFlush(booking);
        } else {
            payment.markFailed(result.failureReason(), now); // the hold stays open; retry with a new key
        }
        paymentRepository.saveAndFlush(payment);
        return new PaymentAttempt(PaymentResponse.from(payment), false, false);
    }

    private Booking ownBooking(Long customerId, String reference) {
        return bookingRepository.findByBookingReference(reference)
                .filter(b -> b.getCustomer().getId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found."));
    }

    private static String storedKey(String prefix, Booking booking, String clientKey) {
        return prefix + ":" + booking.getId() + ":" + clientKey;
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new InvalidRequestException("Idempotency-Key is required: 1 to " + KEY_MAX
                    + " characters from A-Z, a-z, 0-9, '_', '.', ':' and '-'.");
        }
    }
}
