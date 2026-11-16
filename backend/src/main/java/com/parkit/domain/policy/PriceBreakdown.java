package com.parkit.domain.policy;

import java.math.BigDecimal;

/**
 * Price explanation shown before payment (BR-20).
 *
 * @param billableHours  ceil(duration minutes / 60)
 * @param fullDays       billableHours / 24
 * @param remainderHours billableHours mod 24
 * @param dayCharge      fullDays x daily rate
 * @param hourCharge     min(remainderHours x hourly rate, daily rate)
 * @param total          dayCharge + hourCharge
 */
public record PriceBreakdown(long billableHours, long fullDays, long remainderHours,
                             BigDecimal hourlyRate, BigDecimal dailyRate,
                             BigDecimal dayCharge, BigDecimal hourCharge, BigDecimal total) {
}
