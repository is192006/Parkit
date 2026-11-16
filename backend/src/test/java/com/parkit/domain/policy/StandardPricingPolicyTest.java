package com.parkit.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** BR-20 examples at ₹200/day and ₹30/hour, plus cap and rounding. */
class StandardPricingPolicyTest {

    private final PricingPolicy pricing = new StandardPricingPolicy();
    private static final BigDecimal HOURLY = new BigDecimal("30");
    private static final BigDecimal DAILY = new BigDecimal("200");

    @ParameterizedTest(name = "{0} min -> {1}")
    @CsvSource({
            "120, 60.00",     // 2 h
            "1380, 200.00",   // 23 h -> capped at the daily rate
            "1560, 260.00",   // 26 h = 1 day + 2 h
            "3015, 490.00",   // 50 h 15 min -> 51 h = 2 days + 3 h
            "60, 30.00",
            "75, 60.00",      // part hour rounds up
            "1440, 200.00",   // exactly 1 day
            "10080, 1400.00", // 7 days
            "420, 200.00"     // 7 h = 210 -> capped 200
    })
    void brTwentyExamples(long minutes, String expected) {
        assertThat(pricing.price(Duration.ofMinutes(minutes), HOURLY, DAILY).total())
                .isEqualByComparingTo(expected);
    }

    @Test
    void breakdownIsExplained() {
        PriceBreakdown p = pricing.price(Duration.ofMinutes(3015), HOURLY, DAILY);
        assertThat(p.billableHours()).isEqualTo(51);
        assertThat(p.fullDays()).isEqualTo(2);
        assertThat(p.remainderHours()).isEqualTo(3);
        assertThat(p.dayCharge()).isEqualTo(new BigDecimal("400.00"));
        assertThat(p.hourCharge()).isEqualTo(new BigDecimal("90.00"));
        assertThat(p.total().scale()).isEqualTo(2);
    }

    @Test
    void nonPositiveDurationIsRejected() {
        assertThatThrownBy(() -> pricing.price(Duration.ZERO, HOURLY, DAILY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
