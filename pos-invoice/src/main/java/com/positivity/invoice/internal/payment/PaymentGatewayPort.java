package com.positivity.invoice.internal.payment;

import org.jspecify.annotations.NonNull;

/**
 * Port interface for payment gateway operations in pos-invoice.
 *
 * <p>
 * Implementations wire to a payment gateway (e.g., Stripe via pos-accounting
 * REST API). This interface is the internal boundary; pos-invoice has no direct
 * compile-time dependency on pos-accounting internals (ADR-0026).
 *
 * Story #9.
 */
public interface PaymentGatewayPort {

    @NonNull
    GatewayPaymentResult saleCapture(@NonNull PaymentGatewayRequest request);

    @NonNull
    GatewayPaymentResult authorize(@NonNull PaymentGatewayRequest request);

    @NonNull
    GatewayPaymentResult capture(@NonNull GatewayCaptureRequest request);

    @NonNull
    GatewayPaymentResult inquireStatus(@NonNull String gatewayReference);

    @NonNull
    GatewayPaymentResult voidRemainder(@NonNull GatewayVoidRequest request);
}
