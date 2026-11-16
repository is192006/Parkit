package com.parkit.service;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Booking references (BR-12, FR-46): 16 bytes (128 bits) from SecureRandom,
 * Base64-URL encoded without padding: 22 URL-safe characters, random and
 * non-sequential, so a reference cannot be guessed from another one.
 * Fits bookings.booking_reference VARCHAR(32); uniqueness is enforced by the
 * database (a collision is practically impossible).
 */
@Component
public class BookingReferenceGenerator {

    private static final int BYTES = 16;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public String next() {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
