package com.parkit.domain.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * BR-20 pricing with the daily-rate cap on the partial day:
 * <pre>
 * billableHours = ceil(durationMinutes / 60)
 * fullDays      = billableHours / 24
 * remHours      = billableHours mod 24
 * charge        = fullDays x dailyRate + min(remHours x hourlyRate, dailyRate)
 * </pre>
 * Money is BigDecimal, scale 2, HALF_UP (ARCHITECTURE.md §2).
 */
@Component
public class StandardPricingPolicy implements PricingPolicy {

    @Override
    public PriceBreakdown price(Duration duration, BigDecimal hourlyRate, BigDecimal dailyRate) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        long minutes = duration.toMinutes() + (duration.toSecondsPart() > 0 ? 1 : 0);
        long billableHours = (minutes + 59) / 60;
        long fullDays = billableHours / 24;
        long remainderHours = billableHours % 24;

        BigDecimal hourly = scale(hourlyRate);
        BigDecimal daily = scale(dailyRate);
        BigDecimal dayCharge = scale(daily.multiply(BigDecimal.valueOf(fullDays)));
        BigDecimal hourCharge = scale(hourly.multiply(BigDecimal.valueOf(remainderHours)).min(daily));
        return new PriceBreakdown(billableHours, fullDays, remainderHours, hourly, daily,
                dayCharge, hourCharge, scale(dayCharge.add(hourCharge)));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
