package com.parkit.dto.request;

import com.parkit.gateway.SimulatedOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin cancellation (BR-14): a reason is mandatory. {@code simulatedRefundOutcome}
 * lets failed refunds be demonstrated; omitted means SUCCESS.
 */
public record AdminCancelRequest(@NotBlank @Size(max = 255) String reason,
                                 SimulatedOutcome simulatedRefundOutcome) {
}
