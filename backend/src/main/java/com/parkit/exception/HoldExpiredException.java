package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * The 5-minute payment hold has expired.
 * Maps to HTTP 410.
 */
public class HoldExpiredException extends ParkItException {

    public HoldExpiredException(String message) {
        super("HOLD_EXPIRED", HttpStatus.GONE, message);
    }
}
