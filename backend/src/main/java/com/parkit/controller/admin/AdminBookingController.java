package com.parkit.controller.admin;

import com.parkit.domain.enums.BookingStatus;
import com.parkit.dto.request.AdminBookingRequest;
import com.parkit.dto.request.AdminCancelRequest;
import com.parkit.dto.request.PaymentRequest;
import com.parkit.dto.response.AdminBookingResponse;
import com.parkit.dto.response.AdminCancellationResponse;
import com.parkit.dto.response.PageResponse;
import com.parkit.dto.response.PaymentResponse;
import com.parkit.exception.HoldExpiredException;
import com.parkit.security.AuthenticatedUser;
import com.parkit.service.BookingService;
import com.parkit.service.CancellationService;
import com.parkit.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
 * Admin booking creation (hold), search and verification by reference
 * (FR-31, FR-48, FR-55). ADMIN only. Counter payment and admin cancellation
 * belong to a later phase.
 */
@RestController
@RequestMapping("/api/admin/bookings")
public class AdminBookingController {

    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final CancellationService cancellationService;

    public AdminBookingController(BookingService bookingService, PaymentService paymentService,
                                  CancellationService cancellationService) {
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.cancellationService = cancellationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminBookingResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @Valid @RequestBody AdminBookingRequest request) {
        return bookingService.createHoldForCustomer(principal.getUserId(), request);
    }

    /** {@code from}/{@code to} are facility-local dates of the scheduled start, inclusive. */
    @GetMapping
    public PageResponse<AdminBookingResponse> search(
            @RequestParam(name = "status", required = false) BookingStatus status,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20, sort = "scheduledStart", direction = Sort.Direction.DESC) Pageable pageable) {
        return bookingService.adminSearch(status, from, to, pageable);
    }

    /**
     * Records a simulated counter payment for any unexpired hold (BR-23). Same
     * status codes as the online payment: 201 new attempt, 200 replay, 410 expired.
     */
    @PostMapping("/{reference}/counter-payment")
    public ResponseEntity<PaymentResponse> counterPayment(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @PathVariable String reference,
                                                          @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
                                                          @RequestBody(required = false) PaymentRequest request) {
        PaymentService.PaymentAttempt attempt = paymentService.recordCounterPayment(principal.getUserId(),
                reference, idempotencyKey, request == null ? null : request.simulatedOutcome());
        if (attempt.holdExpired()) {
            throw new HoldExpiredException("The payment hold has expired. Please create a new booking.");
        }
        return ResponseEntity.status(attempt.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(attempt.payment());
    }

    /** Cancels a PENDING_PAYMENT or CONFIRMED booking; a paid booking is refunded in full (BR-14). */
    @PostMapping("/{reference}/cancel")
    public AdminCancellationResponse cancel(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @PathVariable String reference,
                                            @Valid @RequestBody AdminCancelRequest request) {
        return cancellationService.adminCancel(principal.getUserId(), reference, request.reason(),
                request.simulatedRefundOutcome());
    }

    @GetMapping("/{reference}")
    public AdminBookingResponse get(@PathVariable String reference) {
        return bookingService.adminGet(reference);
    }
}
