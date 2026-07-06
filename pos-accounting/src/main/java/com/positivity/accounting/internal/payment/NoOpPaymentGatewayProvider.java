package com.positivity.accounting.internal.payment;

import com.positivity.accounting.internal.exception.PaymentGatewayException;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback payment gateway used when no real provider (e.g. Stripe) is configured.
 *
 * All payment operations fail with a clear error. This allows the service to start
 * without a Stripe API key configured (e.g. local dev environments that don't process
 * real payments). Set STRIPE_API_KEY to enable the Stripe implementation.
 */
public class NoOpPaymentGatewayProvider implements PaymentGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(NoOpPaymentGatewayProvider.class);

    public NoOpPaymentGatewayProvider() {
        log.warn(
                "No payment gateway provider configured. Payment processing will be unavailable. "
                        + "Set STRIPE_API_KEY to enable Stripe.");
    }

    @Override
    public @NonNull GatewayPaymentResponse executePayment(@NonNull GatewayPaymentRequest request)
            throws PaymentGatewayException {
        throw new PaymentGatewayException(
                "No payment gateway configured. Set STRIPE_API_KEY to enable payment processing.");
    }

    @Override
    public @NonNull Optional<GatewayPaymentResponse> getPaymentStatus(@NonNull String transactionId)
            throws PaymentGatewayException {
        throw new PaymentGatewayException(
                "No payment gateway configured. Set STRIPE_API_KEY to enable payment processing.");
    }

    @Override
    public @NonNull String getProviderName() {
        return "no-op";
    }
}
