package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * Requested state transition is not allowed by the state machine.
 * Maps to HTTP 409.
 */
public class InvalidStateTransitionException extends ParkItException {

    public InvalidStateTransitionException(String message) {
        super("INVALID_STATE_TRANSITION", HttpStatus.CONFLICT, message);
    }
}
