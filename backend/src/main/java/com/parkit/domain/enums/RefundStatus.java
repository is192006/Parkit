package com.parkit.domain.enums;

/** Refund attempt status (BR-15). Stored as VARCHAR with a CHECK constraint. */
public enum RefundStatus {
    PENDING, SUCCESS, FAILED
}
