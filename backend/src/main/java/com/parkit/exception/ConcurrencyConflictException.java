package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * Lock timeout or exclusion/unique constraint violation caused by a
 * concurrent request. Maps to HTTP 409; the client may retry.
 */
public class ConcurrencyConflictException extends ParkItException {

    public ConcurrencyConflictException(String message) {
        super("CONCURRENCY_CONFLICT", HttpStatus.CONFLICT, message);
    }

    public ConcurrencyConflictException(String message, Throwable cause) {
        super("CONCURRENCY_CONFLICT", HttpStatus.CONFLICT, message, cause);
    }
}
