package com.parkit.scheduler;

import com.parkit.service.BookingExpiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the hold-expiry sweep every 60 s (ARCHITECTURE.md §5). Single
 * application instance, so a single scheduler (SRS §4). Disabled with
 * {@code parkit.scheduler.enabled=false} (tests trigger the service directly).
 * The no-show and reassignment-wait sweeps belong to the check-in phase.
 */
@Component
@ConditionalOnProperty(name = "parkit.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class BookingExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryScheduler.class);

    private final BookingExpiryService expiryService;

    public BookingExpiryScheduler(BookingExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Scheduled(fixedDelayString = "${parkit.scheduler.hold-expiry-interval-ms:60000}",
               initialDelayString = "${parkit.scheduler.hold-expiry-interval-ms:60000}")
    public void expireHolds() {
        try {
            int expired = expiryService.expireOverdueHolds();
            if (expired > 0) {
                log.info("Expired {} payment hold(s)", expired);
            }
        } catch (RuntimeException ex) {
            // Never let one failed run stop future runs; the next run retries.
            log.error("Hold expiry sweep failed", ex);
        }
    }
}
