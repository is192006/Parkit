package com.parkit.domain.policy;

import com.parkit.config.PolicyProperties;
import com.parkit.exception.BookingRuleViolationException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Validates a requested booking interval (BR-05, BR-06), for customer and
 * admin bookings alike. Input is facility-local (BR-28); the result is UTC.
 *
 * <p>Checks, in order: 15-minute grid, end after start, 1 h &lt;= duration
 * &lt;= 7 days, start &gt;= now + 30 min, start &lt;= now + 30 days, start and
 * end within operating hours. All limits come from {@link PolicyProperties}.
 * "Now" is passed in (from the injected Clock), so the class is deterministic.
 */
@Component
public class BookingTimePolicy {

    private final PolicyProperties policy;

    public BookingTimePolicy(PolicyProperties policy) {
        this.policy = policy;
    }

    public BookingInterval validate(LocalDateTime startLocal, LocalDateTime endLocal, Instant now,
                                    ZoneId zone, OperatingHoursPolicy hours) {
        if (!onGrid(startLocal) || !onGrid(endLocal)) {
            throw new BookingRuleViolationException("Start and end times must be in %d-minute steps (e.g. 09:00, 09:15)."
                    .formatted(policy.timeIncrement().toMinutes()));
        }
        Instant start = startLocal.atZone(zone).toInstant();
        Instant end = endLocal.atZone(zone).toInstant();
        if (!end.isAfter(start)) {
            throw new BookingRuleViolationException("End time must be after start time.");
        }
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(policy.minDuration()) < 0) {
            throw new BookingRuleViolationException("Booking must last at least %s."
                    .formatted(human(policy.minDuration())));
        }
        if (duration.compareTo(policy.maxDuration()) > 0) {
            throw new BookingRuleViolationException("Booking must not last longer than %s."
                    .formatted(human(policy.maxDuration())));
        }
        if (start.isBefore(now.plus(policy.minAdvance()))) {
            throw new BookingRuleViolationException("Booking must start at least %s from now."
                    .formatted(human(policy.minAdvance())));
        }
        if (start.isAfter(now.plus(policy.bookingHorizon()))) {
            throw new BookingRuleViolationException("Booking must start within %s from now."
                    .formatted(human(policy.bookingHorizon())));
        }
        if (!hours.allowsStartAt(startLocal.toLocalTime())) {
            throw new BookingRuleViolationException("The facility is closed at the requested start time.");
        }
        if (!hours.allowsEndAt(endLocal.toLocalTime())) {
            throw new BookingRuleViolationException("The facility is closed at the requested end time.");
        }
        return new BookingInterval(start, end);
    }

    /** Whole seconds-of-day divisible by the increment, no sub-second part (BR-06.1). */
    private boolean onGrid(LocalDateTime t) {
        long step = policy.timeIncrement().toSeconds();
        return t.getNano() == 0 && t.toLocalTime().toSecondOfDay() % step == 0;
    }

    private static String human(Duration d) {
        if (d.toDays() > 0 && d.toHoursPart() == 0 && d.toMinutesPart() == 0) {
            return d.toDays() + (d.toDays() == 1 ? " day" : " days");
        }
        if (d.toHours() > 0 && d.toMinutesPart() == 0) {
            return d.toHours() + (d.toHours() == 1 ? " hour" : " hours");
        }
        return d.toMinutes() + " minutes";
    }
}
