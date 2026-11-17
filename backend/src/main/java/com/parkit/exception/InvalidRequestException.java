package com.parkit.exception;

import org.springframework.http.HttpStatus;

/** A request is malformed in a way bean validation cannot express (e.g. a bad Idempotency-Key). Maps to HTTP 400. */
public class InvalidRequestException extends ParkItException {

    public InvalidRequestException(String message) {
        super("INVALID_REQUEST", HttpStatus.BAD_REQUEST, message);
    }
}
