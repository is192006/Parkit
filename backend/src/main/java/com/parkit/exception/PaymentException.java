package com.parkit.exception;

import org.springframework.http.HttpStatus;

/**
 * Payment gateway reported a failure. Maps to HTTP 402.
 */
public class PaymentException extends ParkItException {

    public PaymentException(String message) {
        super("PAYMENT_FAILED", HttpStatus.PAYMENT_REQUIRED, message);
    }

    public PaymentException(String message, Throwable cause) {
        super("PAYMENT_FAILED", HttpStatus.PAYMENT_REQUIRED, message, cause);
    }
}
