package com.parkit.dto.request;

import com.parkit.gateway.SimulatedOutcome;

/** Optional body of a refund retry; omitted outcome means SUCCESS. */
public record RefundRetryRequest(SimulatedOutcome simulatedRefundOutcome) {
}
