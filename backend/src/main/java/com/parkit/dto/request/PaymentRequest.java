package com.parkit.dto.request;

import com.parkit.gateway.SimulatedOutcome;

/**
 * Optional body of a payment attempt. Only the simulated gateway outcome can be
 * chosen; there is deliberately no amount field: the charge is always the stored
 * booking amount (BR-20). Unknown properties such as "amount" are ignored.
 */
public record PaymentRequest(SimulatedOutcome simulatedOutcome) {
}
