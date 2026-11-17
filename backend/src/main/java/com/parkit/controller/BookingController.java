package com.parkit.controller;

import com.parkit.dto.request.BookingHistoryFilter;
import com.parkit.dto.request.BookingRequest;
import com.parkit.dto.request.CustomerCancelRequest;
import com.parkit.dto.request.PaymentRequest;
import com.parkit.dto.response.BookingResponse;
import com.parkit.dto.response.CancellationResponse;
import com.parkit.dto.response.PageResponse;
import com.parkit.dto.response.PaymentResponse;
import com.parkit.dto.response.QuoteResponse;
import com.parkit.dto.response.RefundResponse;
import com.parkit.exception.HoldExpiredException;
import com.parkit.security.AuthenticatedUser;
import com.parkit.service.BookingService;
import com.parkit.service.CancellationService;
import com.parkit.service.PaymentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer quote, hold and booking history (FR-22 to FR-30). CUSTOMER only;
 * the acting customer always comes from the session, never from the request.
 * Bookings are addressed by their non-guessable reference (BR-12).
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final CancellationService cancellationService;

    public BookingController(BookingService bookingService, PaymentService paymentService,
                             CancellationService cancellationService) {
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.cancellationService = cancellationService;
    }

    @PostMapping("/quote")
    public QuoteResponse quote(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody BookingRequest request) {
        return bookingService.quote(principal.getUserId(), request);
    }

    /** Allocates a slot and creates a 5-minute payment hold. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createHold(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @Valid @RequestBody BookingRequest request) {
        return bookingService.createHold(principal.getUserId(), request);
    }

    /** Booking history; abandoned payment holds only under {@code status=ABANDONED}. */
    @GetMapping
    public PageResponse<BookingResponse> history(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(name = "status", defaultValue = "ALL") BookingHistoryFilter status,
            @PageableDefault(size = 20, sort = "scheduledStart", direction = Sort.Direction.DESC) Pageable pageable) {
        return bookingService.history(principal.getUserId(), status, pageable);
    }

    /** The current unexpired payment hold: 200, or 204 when there is none. */
    @GetMapping("/hold")
    public ResponseEntity<BookingResponse> currentHold(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bookingService.currentHold(principal.getUserId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{reference}")
    public BookingResponse get(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable String reference) {
        return bookingService.getOwn(principal.getUserId(), reference);
    }

    /**
     * Pays for the customer's own hold (FR-32 to FR-36). The amount is the stored
     * booking amount; the body may only choose the simulated gateway outcome.
     * 201 for a new attempt (SUCCESS or FAILED), 200 when the Idempotency-Key was
     * already used, 410 when the hold has expired (the FAILED attempt and the
     * PAYMENT_EXPIRED status are already committed when this method throws).
     */
    @PostMapping("/{reference}/payments")
    public ResponseEntity<PaymentResponse> pay(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @PathVariable String reference,
                                               @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
                                               @RequestBody(required = false) PaymentRequest request) {
        PaymentService.PaymentAttempt attempt = paymentService.payOnline(principal.getUserId(), reference,
                idempotencyKey, request == null ? null : request.simulatedOutcome());
        if (attempt.holdExpired()) {
            throw new HoldExpiredException("The payment hold has expired. Please start a new booking.");
        }
        return ResponseEntity.status(attempt.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(attempt.payment());
    }

    @GetMapping("/{reference}/payments")
    public List<PaymentResponse> payments(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @PathVariable String reference) {
        return paymentService.listPayments(principal.getUserId(), reference);
    }

    @GetMapping("/{reference}/refunds")
    public List<RefundResponse> refunds(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable String reference) {
        return paymentService.listRefunds(principal.getUserId(), reference);
    }

    /** Cancels a confirmed booking up to 2 hours before its start; refunds in full (BR-13). */
    @PostMapping("/{reference}/cancel")
    public CancellationResponse cancel(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @PathVariable String reference,
                                       @Valid @RequestBody(required = false) CustomerCancelRequest request) {
        return cancellationService.customerCancel(principal.getUserId(), reference,
                request == null ? null : request.reason());
    }

    @PostMapping("/{reference}/release-hold")
    public BookingResponse releaseHold(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @PathVariable String reference) {
        return bookingService.releaseHold(principal.getUserId(), reference);
    }
}
