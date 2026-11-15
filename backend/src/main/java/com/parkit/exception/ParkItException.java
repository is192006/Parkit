package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class of all ParkIt business exceptions (ARCHITECTURE.md §7).
 * Each subclass fixes its HTTP status; a stable error code lets clients
 * react without parsing messages. Handled polymorphically by
 * {@link GlobalExceptionHandler} through this base type.
 */
public abstract class ParkItException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    protected ParkItException(String errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    protected ParkItException(String errorCode, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
