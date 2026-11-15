package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * Requested resource does not exist (or is not visible to the caller).
 * Maps to HTTP 404.
 */
public class ResourceNotFoundException extends ParkItException {

    public ResourceNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, message);
    }
}
