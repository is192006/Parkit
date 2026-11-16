package com.parkit.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.parkit.domain.enums.BookingStatus;
import com.parkit.exception.HoldExpiredException;
import com.parkit.exception.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Hold lifecycle transitions (BR-10, BR-11, state machine in BUSINESS_RULES.md). */
class BookingTransitionTest {

    private static final Instant NOW = Instant.parse("2030-01-07T04:30:00Z");
    private static final Instant EXPIRY = NOW.plusSeconds(300);

    private static Booking hold() {
        return new Booking("REF", mock(User.class), mock(Vehicle.class), mock(ParkingSlot.class), mock(User.class),
                NOW.plusSeconds(7200), NOW.plusSeconds(14400), EXPIRY,
                new BigDecimal("40.00"), new BigDecimal("200.00"), new BigDecimal("80.00"));
    }

    @Test
    void newBookingIsAnActiveHold() {
        Booking b = hold();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(b.isHoldActive(NOW)).isTrue();
        assertThat(b.isHoldActive(EXPIRY)).isFalse();   // hold_expires_at <= now means expired
    }

    @Test
    void expireOnlyAfterExpiry() {
        Booking b = hold();
        assertThatThrownBy(() -> b.expireHold(NOW)).isInstanceOf(InvalidStateTransitionException.class);
        b.expireHold(EXPIRY);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_EXPIRED);
        assertThatThrownBy(() -> b.expireHold(EXPIRY)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void releaseCancelsWithReasonAndNoConfirmation() {
        Booking b = hold();
        User customer = mock(User.class);
        b.releaseHold(NOW, customer);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getCancellationReason()).isEqualTo(Booking.REASON_HOLD_RELEASED);
        assertThat(b.getCancelledAt()).isEqualTo(NOW);
        assertThat(b.getConfirmedAt()).isNull();
        assertThatThrownBy(() -> b.releaseHold(NOW, customer)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void releaseAfterExpiryIsGone() {
        assertThatThrownBy(() -> hold().releaseHold(EXPIRY, mock(User.class)))
                .isInstanceOf(HoldExpiredException.class);
    }

    @Test
    void confirmWithinHoldSetsConfirmedAtWithoutExtendingHold() {
        Booking b = hold();
        b.confirm(NOW.plusSeconds(299));
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getConfirmedAt()).isEqualTo(NOW.plusSeconds(299));
        assertThat(b.getHoldExpiresAt()).isEqualTo(EXPIRY);
        assertThatThrownBy(() -> b.confirm(NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> b.releaseHold(NOW, mock(User.class)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void confirmAfterExpiryIsRejected() {
        assertThatThrownBy(() -> hold().confirm(EXPIRY)).isInstanceOf(HoldExpiredException.class);
    }
}
