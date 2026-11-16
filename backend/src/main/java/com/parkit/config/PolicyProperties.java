package com.parkit.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Version 1 policy constants (BUSINESS_RULES.md "Policy constants",
 * ARCHITECTURE.md §5), bound from {@code parkit.policy.*} in application.yml.
 * Values are fixed policy, externalized rather than hard-coded; they are
 * validated at startup, so an invalid or missing value stops the application.
 *
 * <p>Facility rates and operating hours are NOT here: they are database-managed.
 *
 * @param minAdvance                 minimum time between now and scheduled start (BR-05)
 * @param paymentHold                payment hold length, never extended (BR-10)
 * @param lateArrivalGrace           check-in allowed until start + grace, then NO_SHOW (BR-16)
 * @param earlyCheckInAllowance      check-in allowed from start - allowance (BR-17)
 * @param customerCancellationCutoff customer may cancel until start - cutoff (BR-13)
 * @param timeIncrement              booking time grid (BR-06.1)
 * @param minDuration                minimum booking duration (BR-06.2)
 * @param maxDuration                maximum booking duration (BR-06.2)
 * @param bookingHorizon             latest start relative to now (BR-06.3)
 * @param overstayPenalty            fixed penalty per overstay incident, INR (BR-19)
 * @param overstayGrace              grace after scheduled end before overstay (BR-19)
 * @param reassignmentWait           maximum reassignment wait (BR-26)
 * @param maxActiveHoldsPerCustomer  active payment holds allowed per customer (BR-10)
 */
@Validated
@ConfigurationProperties(prefix = "parkit.policy")
public record PolicyProperties(
        @NotNull @DurationMin(minutes = 0) Duration minAdvance,
        @NotNull @DurationMin(minutes = 1) Duration paymentHold,
        @NotNull @DurationMin(minutes = 0) Duration lateArrivalGrace,
        @NotNull @DurationMin(minutes = 0) Duration earlyCheckInAllowance,
        @NotNull @DurationMin(minutes = 0) Duration customerCancellationCutoff,
        @NotNull @DurationMin(minutes = 1) Duration timeIncrement,
        @NotNull @DurationMin(minutes = 1) Duration minDuration,
        @NotNull @DurationMin(minutes = 1) Duration maxDuration,
        @NotNull @DurationMin(minutes = 0) Duration bookingHorizon,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 8, fraction = 2)
        BigDecimal overstayPenalty,
        @NotNull @DurationMin(minutes = 0) Duration overstayGrace,
        @NotNull @DurationMin(minutes = 0) Duration reassignmentWait,
        @Min(1) int maxActiveHoldsPerCustomer) {

    @AssertTrue(message = "minDuration must not exceed maxDuration")
    public boolean isDurationRangeValid() {
        return minDuration == null || maxDuration == null || minDuration.compareTo(maxDuration) <= 0;
    }

    @AssertTrue(message = "minDuration and maxDuration must be multiples of timeIncrement")
    public boolean isDurationOnGrid() {
        if (timeIncrement == null || minDuration == null || maxDuration == null || timeIncrement.isZero()) {
            return true;
        }
        long step = timeIncrement.toSeconds();
        return minDuration.toSeconds() % step == 0 && maxDuration.toSeconds() % step == 0;
    }
}
