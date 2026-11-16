package com.parkit.controller.admin;

import com.parkit.domain.enums.BookingStatus;
import com.parkit.dto.request.AdminBookingRequest;
import com.parkit.dto.response.AdminBookingResponse;
import com.parkit.dto.response.PageResponse;
import com.parkit.security.AuthenticatedUser;
import com.parkit.service.BookingService;
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

    public AdminBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
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

    @GetMapping("/{reference}")
    public AdminBookingResponse get(@PathVariable String reference) {
        return bookingService.adminGet(reference);
    }
}
