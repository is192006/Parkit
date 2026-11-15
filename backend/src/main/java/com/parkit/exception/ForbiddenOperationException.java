package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * Caller does not own the resource or may not perform the operation.
 * Maps to HTTP 403.
 */
public class ForbiddenOperationException extends ParkItException {

    public ForbiddenOperationException(String message) {
        super("FORBIDDEN_OPERATION", HttpStatus.FORBIDDEN, message);
    }
}
