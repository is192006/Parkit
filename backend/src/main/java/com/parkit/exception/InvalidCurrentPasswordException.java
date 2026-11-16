package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * The current password supplied for a password change is wrong (FR-05).
 * Maps to HTTP 422 so the frontend does not mistake it for a lost session (401).
 */
public class InvalidCurrentPasswordException extends ParkItException {

    public InvalidCurrentPasswordException() {
        super("INVALID_CURRENT_PASSWORD", HttpStatus.UNPROCESSABLE_ENTITY, "The current password is incorrect.");
    }
}
