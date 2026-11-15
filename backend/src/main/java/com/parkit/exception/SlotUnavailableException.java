package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * No compatible slot is available for the requested interval.
 * Maps to HTTP 409.
 */
public class SlotUnavailableException extends ParkItException {

    public SlotUnavailableException(String message) {
        super("SLOT_UNAVAILABLE", HttpStatus.CONFLICT, message);
    }
}
