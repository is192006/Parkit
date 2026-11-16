package com.parkit.domain.policy;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Computes a booking price (BR-20). An interface because alternative pricing
 * schemes are a realistic extension (ARCHITECTURE.md §4).
 */
public interface PricingPolicy {

    PriceBreakdown price(Duration duration, BigDecimal hourlyRate, BigDecimal dailyRate);
}
