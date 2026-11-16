package com.parkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding and startup-validation tests for {@link PolicyProperties}.
 * Loads the real application.yml; no database or web server is started.
 */
class PolicyPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(PolicyProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void bindsApprovedDefaultsFromApplicationYml() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            PolicyProperties p = ctx.getBean(PolicyProperties.class);
            assertThat(p.minAdvance()).isEqualTo(Duration.ofMinutes(30));
            assertThat(p.paymentHold()).isEqualTo(Duration.ofMinutes(5));
            assertThat(p.lateArrivalGrace()).isEqualTo(Duration.ofMinutes(15));
            assertThat(p.earlyCheckInAllowance()).isEqualTo(Duration.ofMinutes(30));
            assertThat(p.customerCancellationCutoff()).isEqualTo(Duration.ofHours(2));
            assertThat(p.timeIncrement()).isEqualTo(Duration.ofMinutes(15));
            assertThat(p.minDuration()).isEqualTo(Duration.ofHours(1));
            assertThat(p.maxDuration()).isEqualTo(Duration.ofDays(7));
            assertThat(p.bookingHorizon()).isEqualTo(Duration.ofDays(30));
            assertThat(p.overstayPenalty()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(p.overstayGrace()).isEqualTo(Duration.ZERO);
            assertThat(p.reassignmentWait()).isEqualTo(Duration.ofMinutes(15));
            assertThat(p.maxActiveHoldsPerCustomer()).isEqualTo(1);
        });
    }

    @Test
    void failsWhenRequiredValueMissing() {
        runner.withPropertyValues("parkit.policy.payment-hold=")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("paymentHold"));
    }

    @Test
    void failsOnNegativeDuration() {
        assertStartupFails("parkit.policy.min-advance=-5m", "minAdvance");
    }

    @Test
    void failsOnNonPositivePenalty() {
        assertStartupFails("parkit.policy.overstay-penalty=0", "overstayPenalty");
    }

    @Test
    void failsOnZeroHoldsPerCustomer() {
        assertStartupFails("parkit.policy.max-active-holds-per-customer=0", "maxActiveHoldsPerCustomer");
    }

    @Test
    void failsWhenMinDurationExceedsMax() {
        assertStartupFails("parkit.policy.min-duration=8d", "minDuration must not exceed maxDuration");
    }

    @Test
    void failsWhenDurationsOffTheGrid() {
        assertStartupFails("parkit.policy.min-duration=70m", "multiples of timeIncrement");
    }

    private void assertStartupFails(String property, String expectedMessage) {
        runner.withPropertyValues(property).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining(expectedMessage);
        });
    }
}
