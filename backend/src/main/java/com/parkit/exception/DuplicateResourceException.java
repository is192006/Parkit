package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * Resource already exists (email, registration number, active hold).
 * Maps to HTTP 409.
 */
public class DuplicateResourceException extends ParkItException {

    public DuplicateResourceException(String message) {
        super("DUPLICATE_RESOURCE", HttpStatus.CONFLICT, message);
    }
}
