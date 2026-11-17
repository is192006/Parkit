package com.parkit.gateway;

/**
 * Abstraction over the payment provider (ARCHITECTURE.md §4). Only the simulated
 * implementation exists; a real provider would replace just that class.
 * Implementations report declined payments as a failed {@link GatewayResult},
 * never by throwing.
 */
public interface PaymentGateway {

    GatewayResult charge(ChargeRequest request);

    GatewayResult refund(GatewayRefundRequest request);
}
