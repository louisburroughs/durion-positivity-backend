package com.positivity.accounting.internal.payment;

import com.positivity.accounting.internal.exception.PaymentGatewayException;
import jakarta.validation.Valid;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Contract for payment gateway providers.
 *
 * Implementations handle payment processing, idempotency, and transaction
 * tracking.
 */
public interface PaymentGatewayProvider {

    /**
     * Execute a payment through the gateway.
     *
     * Implementations MUST support idempotency: if called with the same
     * idempotencyKey, they
     * must return the same result without re-charging.
     *
     * @param request payment execution request
     * @return gateway response with transaction ID and status
     * @throws PaymentGatewayException if gateway call fails
     */
    @NonNull
    GatewayPaymentResponse executePayment(@NonNull @Valid GatewayPaymentRequest request) throws PaymentGatewayException;

    /**
     * Check the status of a previously initiated payment.
     *
     * @param transactionId the gateway transaction ID
     * @return gateway response with current status, or empty if not found
     * @throws PaymentGatewayException if status check fails
     */
    @NonNull
    Optional<GatewayPaymentResponse> getPaymentStatus(@NonNull String transactionId) throws PaymentGatewayException;

    /**
     * Find the charge the gateway holds under a payment reference. The pay command's idempotency key is its
     * {@code paymentRef}, so this answers "did the gateway charge this payment?" without a transaction id. It exists for
     * the {@code GATEWAY_PENDING} orphan: the call timed out or the process died after the charge was sent, so the
     * transaction id was never recorded, and settlement or an operator needs to learn the outcome from the reference
     * alone.
     *
     * @param paymentRef the payment reference (the idempotency key of the charge)
     * @return the charge held under this reference, or empty when the gateway holds none
     * @throws PaymentGatewayException if the lookup fails (the outcome stays unknown)
     */
    @NonNull
    Optional<GatewayPaymentResponse> findByReference(@NonNull String paymentRef) throws PaymentGatewayException;

    /**
     * Get a human-readable name for this gateway provider.
     *
     * @return provider name (e.g., "Stripe", "Square")
     */
    @NonNull
    String getProviderName();

    /**
     * Payment status from gateway perspective.
     */
    enum GatewayPaymentStatus {
        /**
         * Payment authorized but not yet captured/settled.
         */
        AUTHORIZED,
        /**
         * Payment captured and charged successfully.
         */
        SUCCEEDED,
        /**
         * Payment failed at the gateway.
         */
        FAILED,
        /**
         * Payment is pending (e.g., awaiting customer action or settlement).
         */
        PENDING,
        /**
         * Payment was declined by merchant rules or gateway policies.
         */
        DECLINED
    }
}
