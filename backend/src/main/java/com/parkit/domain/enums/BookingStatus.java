package com.parkit.domain.enums;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Booking lifecycle status. The allowed transitions are the state machine
 * in BUSINESS_RULES.md; all other transitions are invalid.
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CHECKED_IN,
    COMPLETED,
    CANCELLED,
    PAYMENT_EXPIRED,
    NO_SHOW;

    /** Statuses covered by the overlap exclusion constraints (DATABASE_DESIGN.md §6). */
    public static final Set<BookingStatus> ACTIVE =
            Collections.unmodifiableSet(EnumSet.of(PENDING_PAYMENT, CONFIRMED, CHECKED_IN));

    private static final Map<BookingStatus, Set<BookingStatus>> TRANSITIONS = new EnumMap<>(BookingStatus.class);

    static {
        TRANSITIONS.put(PENDING_PAYMENT, EnumSet.of(CONFIRMED, PAYMENT_EXPIRED, CANCELLED));
        TRANSITIONS.put(CONFIRMED, EnumSet.of(CHECKED_IN, CANCELLED, NO_SHOW));
        TRANSITIONS.put(CHECKED_IN, EnumSet.of(COMPLETED));
        TRANSITIONS.put(COMPLETED, EnumSet.noneOf(BookingStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(BookingStatus.class));
        TRANSITIONS.put(PAYMENT_EXPIRED, EnumSet.noneOf(BookingStatus.class));
        TRANSITIONS.put(NO_SHOW, EnumSet.noneOf(BookingStatus.class));
    }

    public boolean canTransitionTo(BookingStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
