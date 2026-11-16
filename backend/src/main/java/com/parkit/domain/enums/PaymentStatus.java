package com.parkit.domain.enums;

/** Payment attempt status. Stored as VARCHAR with a CHECK constraint. */
public enum PaymentStatus {
    PENDING, SUCCESS, FAILED
}
