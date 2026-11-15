package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * A business rule was violated (time, limits, hours, cancellation or check-in window).
 * Maps to HTTP 422.
 */
public class BookingRuleViolationException extends ParkItException {

    public BookingRuleViolationException(String message) {
        super("BOOKING_RULE_VIOLATION", HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
