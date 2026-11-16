package com.parkit.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkit.config.PolicyProperties;
import com.parkit.exception.BookingRuleViolationException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** BR-05 and BR-06 with a fixed "now" (2030-01-07 10:00 IST) and the Version 1 policy values. */
class BookingTimePolicyTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.of(2030, 1, 7, 10, 0);
    private static final Instant NOW = NOW_LOCAL.atZone(IST).toInstant();

    static final PolicyProperties POLICY = com.parkit.support.TestPolicies.version1();

    private final BookingTimePolicy policy = new BookingTimePolicy(POLICY);
    private final OperatingHoursPolicy always = OperatingHoursPolicy.always();

    private BookingInterval validate(LocalDateTime s, LocalDateTime e, OperatingHoursPolicy hours) {
        return policy.validate(s, e, NOW, IST, hours);
    }

    private void rejects(LocalDateTime s, LocalDateTime e, OperatingHoursPolicy hours, String message) {
        assertThatThrownBy(() -> validate(s, e, hours))
                .isInstanceOf(BookingRuleViolationException.class).hasMessageContaining(message);
    }

    @Test
    void validIntervalIsConvertedToUtc() {
        BookingInterval i = validate(NOW_LOCAL.plusMinutes(30), NOW_LOCAL.plusMinutes(90), always);
        assertThat(i.start()).isEqualTo(Instant.parse("2030-01-07T05:00:00Z"));
        assertThat(i.duration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void gridRequiresQuarterHoursAndZeroSeconds() {
        rejects(NOW_LOCAL.plusMinutes(40), NOW_LOCAL.plusHours(3), always, "15-minute");
        rejects(NOW_LOCAL.plusHours(1), NOW_LOCAL.plusHours(3).plusSeconds(1), always, "15-minute");
        rejects(NOW_LOCAL.plusHours(1).plusNanos(1), NOW_LOCAL.plusHours(3), always, "15-minute");
    }

    @Test
    void durationLimitsAreInclusive() {
        LocalDateTime s = NOW_LOCAL.plusHours(1);
        rejects(s, s, always, "after start");
        rejects(s, s.minusHours(1), always, "after start");
        rejects(s, s.plusMinutes(45), always, "at least 1 hour");
        validate(s, s.plusHours(1), always);
        validate(s, s.plusDays(7), always);
        rejects(s, s.plusDays(7).plusMinutes(15), always, "7 days");
    }

    @Test
    void advanceAndHorizonLimitsAreInclusive() {
        rejects(NOW_LOCAL.plusMinutes(15), NOW_LOCAL.plusHours(2), always, "30 minutes");
        validate(NOW_LOCAL.plusMinutes(30), NOW_LOCAL.plusHours(2), always);
        validate(NOW_LOCAL.plusDays(30), NOW_LOCAL.plusDays(30).plusHours(1), always);
        rejects(NOW_LOCAL.plusDays(30).plusMinutes(15), NOW_LOCAL.plusDays(30).plusHours(2), always, "30 days");
    }

    @Test
    void operatingHoursBoundaries() {
        OperatingHoursPolicy day = new OperatingHoursPolicy(LocalTime.of(8, 0), LocalTime.of(20, 0), false);
        LocalDateTime tomorrow = NOW_LOCAL.toLocalDate().plusDays(1).atStartOfDay();
        validate(tomorrow.plusHours(8), tomorrow.plusHours(20), day);           // 08:00-20:00
        rejects(tomorrow.plusHours(20), tomorrow.plusHours(22), day, "start time");
        rejects(tomorrow.plusHours(6), tomorrow.plusHours(9), day, "start time");
        rejects(tomorrow.plusHours(18), tomorrow.plusHours(21), day, "end time");
        // BR-06.4: may span closed hours; start and end are each within open hours.
        validate(tomorrow.plusHours(18), tomorrow.plusDays(1).plusHours(9), day);
    }

    @Test
    void overnightOperatingHours() {
        OperatingHoursPolicy night = new OperatingHoursPolicy(LocalTime.of(18, 0), LocalTime.of(2, 0), false);
        LocalDateTime tomorrow = NOW_LOCAL.toLocalDate().plusDays(1).atStartOfDay();
        validate(tomorrow.plusHours(18), tomorrow.plusDays(1).plusHours(2), night);  // 18:00 -> 02:00
        validate(tomorrow.plusHours(23), tomorrow.plusDays(1).plusHours(1), night);
        rejects(tomorrow.plusHours(2), tomorrow.plusHours(19), night, "start time");
        rejects(tomorrow.plusHours(19), tomorrow.plusDays(1).plusHours(3), night, "end time");
        validate(tomorrow.plusHours(19), tomorrow.plusDays(1).plusHours(19), night); // spans closed hours
    }
}
