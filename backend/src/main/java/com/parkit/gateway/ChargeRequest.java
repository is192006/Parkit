package com.parkit.gateway;

import java.math.BigDecimal;

/** A charge sent to the gateway. The amount always comes from the stored booking, never from the client. */
public record ChargeRequest(String idempotencyKey, String bookingReference, BigDecimal amount,
                            SimulatedOutcome simulatedOutcome) {
}
