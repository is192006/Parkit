package com.parkit.domain.policy;

import java.time.Duration;
import java.time.Instant;

/** A validated booking interval as UTC instants, half-open {@code [start, end)} (BR-07). */
public record BookingInterval(Instant start, Instant end) {

    public Duration duration() {
        return Duration.between(start, end);
    }
}
