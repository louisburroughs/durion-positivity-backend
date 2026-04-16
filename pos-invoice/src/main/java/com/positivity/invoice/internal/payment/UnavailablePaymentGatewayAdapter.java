package com.positivity.invoice.internal.payment;

import com.positivity.invoice.internal.exception.PaymentGatewayException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default fallback adapter that keeps the invoice service bootable when no real
 * payment gateway integration has been wired yet.
 *
 * <p>A concrete adapter should replace this bean via {@link PaymentGatewayPort}
 * when gateway connectivity is implemented for the environment.</p>
 */
public class UnavailablePaymentGatewayAdapter implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(UnavailablePaymentGatewayAdapter.class);

    public UnavailablePaymentGatewayAdapter() {
        log.warn("No PaymentGatewayPort implementation configured. Payment operations will fail until a real gateway adapter is provided.");
    }

    @Override
    public @NonNull GatewayPaymentResult saleCapture(@NonNull PaymentGatewayRequest request) {
        throw unavailable();
    }

    @Override
    public @NonNull GatewayPaymentResult authorize(@NonNull PaymentGatewayRequest request) {
        throw unavailable();
    }

    @Override
    public @NonNull GatewayPaymentResult capture(@NonNull GatewayCaptureRequest request) {
        throw unavailable();
    }

    @Override
    public @NonNull GatewayPaymentResult inquireStatus(@NonNull String gatewayReference) {
        throw unavailable();
    }

    @Override
    public @NonNull GatewayPaymentResult voidRemainder(@NonNull GatewayVoidRequest request) {
        throw unavailable();
    }

    @Override
    public @NonNull GatewayPaymentResult refund(@NonNull GatewayRefundRequest request) {
        throw unavailable();
    }

    private PaymentGatewayException unavailable() {
        return new PaymentGatewayException(
                "No PaymentGatewayPort implementation is configured for pos-invoice. "
                        + "Add a concrete payment gateway adapter before invoking payment operations.");
    }
}
