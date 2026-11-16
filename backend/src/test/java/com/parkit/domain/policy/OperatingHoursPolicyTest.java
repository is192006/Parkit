package com.parkit.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** BR-06.5 operating hours, including the approved boundary rule (D3) and overnight windows. */
class OperatingHoursPolicyTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final OperatingHoursPolicy day = new OperatingHoursPolicy(LocalTime.of(8, 0), LocalTime.of(20, 0), false);
    private final OperatingHoursPolicy night = new OperatingHoursPolicy(LocalTime.of(18, 0), LocalTime.of(2, 0), false);

    @Test
    void sameDayWindowBoundaries() {
        assertThat(day.allowsStartAt(LocalTime.of(8, 0))).isTrue();     // opening inclusive for start
        assertThat(day.allowsStartAt(LocalTime.of(19, 45))).isTrue();
        assertThat(day.allowsStartAt(LocalTime.of(20, 0))).isFalse();   // closing exclusive for start
        assertThat(day.allowsStartAt(LocalTime.of(7, 45))).isFalse();
        assertThat(day.allowsEndAt(LocalTime.of(20, 0))).isTrue();      // closing inclusive for end
        assertThat(day.allowsEndAt(LocalTime.of(8, 0))).isFalse();      // opening exclusive for end
        assertThat(day.allowsEndAt(LocalTime.of(8, 15))).isTrue();
        assertThat(day.allowsEndAt(LocalTime.of(20, 15))).isFalse();
    }

    @Test
    void overnightWindowIsOneContinuousWindowAcrossMidnight() {
        assertThat(night.allowsStartAt(LocalTime.of(18, 0))).isTrue();
        assertThat(night.allowsStartAt(LocalTime.of(23, 45))).isTrue();
        assertThat(night.allowsStartAt(LocalTime.MIDNIGHT)).isTrue();
        assertThat(night.allowsStartAt(LocalTime.of(1, 45))).isTrue();
        assertThat(night.allowsStartAt(LocalTime.of(2, 0))).isFalse();
        assertThat(night.allowsStartAt(LocalTime.of(12, 0))).isFalse();
        assertThat(night.allowsEndAt(LocalTime.of(2, 0))).isTrue();
        assertThat(night.allowsEndAt(LocalTime.MIDNIGHT)).isTrue();
        assertThat(night.allowsEndAt(LocalTime.of(18, 0))).isFalse();
        assertThat(night.allowsEndAt(LocalTime.of(18, 15))).isTrue();
        assertThat(night.allowsEndAt(LocalTime.of(2, 15))).isFalse();
    }

    @Test
    void twentyFourHoursAllowsEverything() {
        OperatingHoursPolicy always = OperatingHoursPolicy.always();
        assertThat(always.allowsStartAt(LocalTime.of(3, 0))).isTrue();
        assertThat(always.allowsEndAt(LocalTime.MIDNIGHT)).isTrue();
        assertThat(always.isOpenAt(LocalTime.of(23, 59))).isTrue();
    }

    @Test
    void equalTimesRequireTwentyFourHours() {
        assertThatThrownBy(() -> new OperatingHoursPolicy(LocalTime.NOON, LocalTime.NOON, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openMinutesCountOnlyOpenTime() {
        // 19:00 to next day 09:00 with 08:00-20:00: 60 min (19-20) + 60 min (08-09).
        Instant from = LocalDateTime.of(2030, 1, 7, 19, 0).atZone(IST).toInstant();
        Instant to = LocalDateTime.of(2030, 1, 8, 9, 0).atZone(IST).toInstant();
        assertThat(day.openMinutesBetween(from, to, IST)).isEqualTo(120);
        // Overnight window 18:00-02:00: 01:00 to 19:00 -> 60 (01-02) + 60 (18-19).
        Instant f2 = LocalDateTime.of(2030, 1, 8, 1, 0).atZone(IST).toInstant();
        Instant t2 = LocalDateTime.of(2030, 1, 8, 19, 0).atZone(IST).toInstant();
        assertThat(night.openMinutesBetween(f2, t2, IST)).isEqualTo(120);
        assertThat(OperatingHoursPolicy.always().openMinutesBetween(from, to, IST)).isEqualTo(14 * 60);
        assertThat(day.openMinutesBetween(to, from, IST)).isZero();
    }
}
