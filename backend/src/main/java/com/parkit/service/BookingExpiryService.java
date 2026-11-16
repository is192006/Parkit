package com.parkit.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sweeps expired payment holds: PENDING_PAYMENT with hold_expires_at &lt;= now
 * becomes PAYMENT_EXPIRED (BR-10). One conditional bulk UPDATE
 * (ARCHITECTURE.md §5): idempotent, and safe alongside other transactions
 * because the WHERE clause is re-checked on any row it waits for, and it
 * bumps the JPA version so a concurrent entity update fails cleanly (409).
 *
 * <p>Correctness never depends on this sweep: every rule compares
 * hold_expires_at with the current instant (BR-08). Each expiry is audited
 * with actor type SYSTEM (BR-27).
 */
@Service
public class BookingExpiryService {

    private static final String EXPIRE_HOLDS_SQL = """
            UPDATE bookings
               SET booking_status = 'PAYMENT_EXPIRED', version = version + 1, updated_at = ?
             WHERE booking_status = 'PENDING_PAYMENT' AND hold_expires_at <= ?
            RETURNING booking_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final Clock clock;

    public BookingExpiryService(JdbcTemplate jdbcTemplate, AuditService auditService, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** @return the number of holds expired by this run */
    @Transactional
    public int expireOverdueHolds() {
        Timestamp now = Timestamp.from(Instant.now(clock));
        List<Long> expired = jdbcTemplate.queryForList(EXPIRE_HOLDS_SQL, Long.class, now, now);
        for (Long bookingId : expired) {
            auditService.recordSystemAction(AuditService.HOLD_EXPIRED, AuditService.ENTITY_BOOKING, bookingId,
                    Map.of("trigger", "SCHEDULER"));
        }
        return expired.size();
    }
}
