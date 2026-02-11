package com.positivity.accounting.internal.payment;

import java.math.BigDecimal;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.exception.PaymentGatewayException;

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
    GatewayPaymentResponse executePayment(@NonNull GatewayPaymentRequest request)
            throws PaymentGatewayException;

    /**
     * Check the status of a previously initiated payment.
     *
     * @param transactionId the gateway transaction ID
     * @return gateway response with current status, or empty if not found
     * @throws PaymentGatewayException if status check fails
     */
    @NonNull
    Optional<GatewayPaymentResponse> getPaymentStatus(@NonNull String transactionId)
            throws PaymentGatewayException;

    /**
     * Get a human-readable name for this gateway provider.
     *
     * @return provider name (e.g., "Stripe", "Square")
     */
    @NonNull
    String getProviderName();

    /**
     * Dto: Gateway payment request.
     */
    record GatewayPaymentRequest(
            @NonNull String idempotencyKey,
            @NonNull BigDecimal amount,
            @NonNull String currency,
            @NonNull PaymentMethod paymentMethod,
            @NonNull String vendorId,
            String memo,
            String... metadata) {
    }

    /**
     * Dto: Gateway payment response.
     */
    record GatewayPaymentResponse(
            @NonNull String transactionId,
            @NonNull GatewayPaymentStatus status,
            String authorizationCode,
            String failureReason,
            String rawResponse) {
    }

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
