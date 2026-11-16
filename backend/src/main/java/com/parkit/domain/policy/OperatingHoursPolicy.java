package com.parkit.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Facility operating hours (BR-06.5), evaluated in facility-local time.
 *
 * <p>One daily open window {@code [opening, closing)}:
 * <ul>
 *   <li>{@code open24Hours}: every instant is open;</li>
 *   <li>{@code opening < closing}: same-day window, e.g. 08:00-20:00;</li>
 *   <li>{@code closing < opening}: the window crosses midnight, e.g. 18:00-02:00
 *       is ONE continuous window from 18:00 to 02:00 the next day.</li>
 * </ul>
 *
 * <p>Boundaries (approved decision D3): the opening time is inclusive for a
 * booking start and the closing time is inclusive for a booking end, so with
 * 08:00-20:00 a booking may start at 08:00 and end at 20:00, but may not start
 * at 20:00 or end at 08:00. Start and end are checked separately: a booking
 * may span closed hours (BR-06.4).
 *
 * <p>Plain Java, immutable and side-effect free, so it is unit-tested without Spring.
 */
public final class OperatingHoursPolicy {

    private final LocalTime opening;
    private final LocalTime closing;
    private final boolean open24Hours;

    public OperatingHoursPolicy(LocalTime opening, LocalTime closing, boolean open24Hours) {
        this.opening = Objects.requireNonNull(opening);
        this.closing = Objects.requireNonNull(closing);
        this.open24Hours = open24Hours;
        if (!open24Hours && opening.equals(closing)) {
            throw new IllegalArgumentException("opening equals closing is valid only when open 24 hours");
        }
    }

    public static OperatingHoursPolicy always() {
        return new OperatingHoursPolicy(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, true);
    }

    private boolean overnight() {
        return closing.isBefore(opening);
    }

    /** True if the facility is open at this local time: {@code [opening, closing)}. */
    public boolean isOpenAt(LocalTime t) {
        if (open24Hours) {
            return true;
        }
        boolean afterOpening = !t.isBefore(opening);
        boolean beforeClosing = t.isBefore(closing);
        return overnight() ? (afterOpening || beforeClosing) : (afterOpening && beforeClosing);
    }

    /** A booking (vehicle entry) may start at this local time: opening inclusive, closing exclusive. */
    public boolean allowsStartAt(LocalTime t) {
        return isOpenAt(t);
    }

    /** A booking (vehicle exit) may end at this local time: opening exclusive, closing inclusive. */
    public boolean allowsEndAt(LocalTime t) {
        if (open24Hours) {
            return true;
        }
        boolean afterOpening = t.isAfter(opening);
        boolean atOrBeforeClosing = !t.isAfter(closing);
        return overnight() ? (afterOpening || atOrBeforeClosing) : (afterOpening && atOrBeforeClosing);
    }

    /**
     * Open minutes within {@code [from, to)} (BR-19 overstay counts open time
     * only). Provided now for the check-out phase; closed hours never count.
     */
    public long openMinutesBetween(Instant from, Instant to, ZoneId zone) {
        if (!to.isAfter(from)) {
            return 0;
        }
        if (open24Hours) {
            return Duration.between(from, to).toMinutes();
        }
        long seconds = 0;
        LocalDate first = from.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = to.atZone(zone).toLocalDate();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            Instant windowStart = day.atTime(opening).atZone(zone).toInstant();
            Instant windowEnd = (overnight() ? day.plusDays(1) : day).atTime(closing).atZone(zone).toInstant();
            Instant s = windowStart.isAfter(from) ? windowStart : from;
            Instant e = windowEnd.isBefore(to) ? windowEnd : to;
            if (e.isAfter(s)) {
                seconds += Duration.between(s, e).toSeconds();
            }
        }
        return seconds / 60;
    }

    public LocalTime opening() {
        return opening;
    }

    public LocalTime closing() {
        return closing;
    }

    public boolean open24Hours() {
        return open24Hours;
    }
}
