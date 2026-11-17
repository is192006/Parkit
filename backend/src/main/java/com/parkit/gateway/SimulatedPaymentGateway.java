package com.parkit.gateway;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Returns the requested simulated outcome with a generated reference. No money moves. */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    static final String CHARGE_FAILURE = "Simulated payment failure.";
    static final String REFUND_FAILURE = "Simulated refund failure.";

    @Override
    public GatewayResult charge(ChargeRequest request) {
        if (SimulatedOutcome.orDefault(request.simulatedOutcome()) == SimulatedOutcome.FAILURE) {
            return GatewayResult.failed(CHARGE_FAILURE);
        }
        return GatewayResult.succeeded("TXN-" + token());
    }

    @Override
    public GatewayResult refund(GatewayRefundRequest request) {
        if (SimulatedOutcome.orDefault(request.simulatedOutcome()) == SimulatedOutcome.FAILURE) {
            return GatewayResult.failed(REFUND_FAILURE);
        }
        return GatewayResult.succeeded("RFD-" + token());
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}
