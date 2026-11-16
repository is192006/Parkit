package com.parkit.support;

import com.parkit.config.PolicyProperties;
import java.math.BigDecimal;
import java.time.Duration;

/** The Version 1 policy constants (BUSINESS_RULES.md) for unit tests without Spring. */
public final class TestPolicies {

    private TestPolicies() {
    }

    public static PolicyProperties version1() {
        return new PolicyProperties(
                Duration.ofMinutes(30), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30),
                Duration.ofHours(2), Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofDays(7),
                Duration.ofDays(30), new BigDecimal("100.00"), Duration.ZERO, Duration.ofMinutes(15), 1);
    }
}
