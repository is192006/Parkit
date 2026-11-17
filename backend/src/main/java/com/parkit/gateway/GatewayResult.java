package com.parkit.gateway;

/**
 * Result of a gateway call. {@code reference} is the gateway's transaction or
 * refund reference (present only on success); {@code failureReason} only on failure.
 */
public record GatewayResult(boolean success, String reference, String failureReason) {

    public static GatewayResult succeeded(String reference) {
        return new GatewayResult(true, reference, null);
    }

    public static GatewayResult failed(String failureReason) {
        return new GatewayResult(false, null, failureReason);
    }
}
