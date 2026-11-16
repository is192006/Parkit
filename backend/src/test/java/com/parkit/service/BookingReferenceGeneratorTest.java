package com.parkit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** BR-12: 128-bit, URL-safe, non-sequential references. */
class BookingReferenceGeneratorTest {

    private final BookingReferenceGenerator generator = new BookingReferenceGenerator();

    @Test
    void referencesAreUrlSafe22CharsAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String ref = generator.next();
            assertThat(ref).hasSize(22).matches("[A-Za-z0-9_-]+");
            assertThat(seen.add(ref)).isTrue();
        }
    }
}
