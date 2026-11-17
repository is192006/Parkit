package com.parkit.dto.request;

import jakarta.validation.constraints.Size;

/** Optional body of a customer cancellation; the reason defaults to CUSTOMER_REQUESTED. */
public record CustomerCancelRequest(@Size(max = 255) String reason) {
}
