package com.parkit.controller;

import com.parkit.dto.request.BookingHistoryFilter;
import com.parkit.dto.request.BookingRequest;
import com.parkit.dto.response.BookingResponse;
import com.parkit.dto.response.PageResponse;
import com.parkit.dto.response.QuoteResponse;
import com.parkit.security.AuthenticatedUser;
import com.parkit.service.BookingService;
import jakarta.validation.Valid;
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

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
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

    @PostMapping("/{reference}/release-hold")
    public BookingResponse releaseHold(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @PathVariable String reference) {
        return bookingService.releaseHold(principal.getUserId(), reference);
    }
}
