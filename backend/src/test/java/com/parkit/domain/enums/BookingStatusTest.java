package com.parkit.domain.enums;

import static com.parkit.domain.enums.BookingStatus.CANCELLED;
import static com.parkit.domain.enums.BookingStatus.CHECKED_IN;
import static com.parkit.domain.enums.BookingStatus.COMPLETED;
import static com.parkit.domain.enums.BookingStatus.CONFIRMED;
import static com.parkit.domain.enums.BookingStatus.NO_SHOW;
import static com.parkit.domain.enums.BookingStatus.PAYMENT_EXPIRED;
import static com.parkit.domain.enums.BookingStatus.PENDING_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies the booking state machine in BUSINESS_RULES.md. */
class BookingStatusTest {

    @Test
    void allowsExactlyTheDocumentedTransitions() {
        assertAllowed(PENDING_PAYMENT, EnumSet.of(CONFIRMED, PAYMENT_EXPIRED, CANCELLED));
        assertAllowed(CONFIRMED, EnumSet.of(CHECKED_IN, CANCELLED, NO_SHOW));
        assertAllowed(CHECKED_IN, EnumSet.of(COMPLETED));
        for (BookingStatus terminal : EnumSet.of(COMPLETED, CANCELLED, PAYMENT_EXPIRED, NO_SHOW)) {
            assertAllowed(terminal, EnumSet.noneOf(BookingStatus.class));
        }
    }

    @Test
    void terminalAndActiveSets() {
        assertThat(EnumSet.allOf(BookingStatus.class).stream().filter(BookingStatus::isTerminal))
                .containsExactlyInAnyOrder(COMPLETED, CANCELLED, PAYMENT_EXPIRED, NO_SHOW);
        assertThat(BookingStatus.ACTIVE).containsExactlyInAnyOrder(PENDING_PAYMENT, CONFIRMED, CHECKED_IN);
        assertThat(CONFIRMED.isActive()).isTrue();
        assertThat(NO_SHOW.isActive()).isFalse();
    }

    private static void assertAllowed(BookingStatus from, Set<BookingStatus> allowed) {
        for (BookingStatus to : BookingStatus.values()) {
            assertThat(from.canTransitionTo(to))
                    .as("%s -> %s", from, to)
                    .isEqualTo(allowed.contains(to));
        }
    }
}
