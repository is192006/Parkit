package com.parkit.gateway;

import java.math.BigDecimal;

/** A refund sent to the gateway, referring to the original transaction. */
public record GatewayRefundRequest(String idempotencyKey, String transactionReference, BigDecimal amount,
                                   SimulatedOutcome simulatedOutcome) {
}
