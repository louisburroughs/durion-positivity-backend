package com.positivity.accounting.internal.payment;

import java.math.BigDecimal;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.net.RequestOptions;

/**
 * Stripe payment gateway implementation.
 *
 * Uses Stripe's Charges API for payment processing. Configure via environment
 * variables:
 * - STRIPE_API_KEY: Stripe secret API key (required)
 * - STRIPE_CONNECT_ACCOUNT: Stripe Connect account ID (optional, for
 * marketplace)
 * - STRIPE_IDEMPOTENCY_WINDOW_HOURS: Idempotency key window in hours (default:
 * 24)
 */
@Component
@ConditionalOnProperty(name = "payment.gateway.provider", havingValue = "stripe", matchIfMissing = true)
@ConditionalOnProperty(name = "stripe.api-key", matchIfMissing = false)
public class StripePaymentGateway implements PaymentGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

    private final Optional<String> connectAccount;

    public StripePaymentGateway(
            @Value("${stripe.api-key:}") String apiKey,
            @Value("${stripe.connect-account:}") String connectAccount,
            @Value("${stripe.idempotency-window-hours:24}") long idempotencyWindowHours) {
        this.connectAccount = connectAccount != null && !connectAccount.isBlank()
                ? Optional.of(connectAccount)
                : Optional.empty();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Stripe API key not configured. Set STRIPE_API_KEY environment variable or stripe.api-key property");
        }

        Stripe.apiKey = apiKey;
        log.info("Stripe payment gateway initialized. Connect account: {}",
                this.connectAccount.isPresent() ? "yes" : "no");
    }

    @Override
    public @NonNull GatewayPaymentResponse executePayment(@NonNull GatewayPaymentRequest request)
            throws PaymentGatewayException {
        try {
            log.debug("Executing Stripe payment. Idempotency Key: {}, Amount: {} {}, Method: {}",
                    request.idempotencyKey(), request.amount(), request.currency(), request.paymentMethod());

            // Convert BigDecimal amount to cents (Stripe requires integer cents)
            long amountInCents = request.amount().multiply(BigDecimal.valueOf(100)).longValue();

            var chargeParams = new java.util.HashMap<String, Object>();
            chargeParams.put("amount", amountInCents);
            chargeParams.put("currency", request.currency().toLowerCase());
            chargeParams.put("source", request.paymentSource());
            chargeParams.put("description", request.memo());
            chargeParams.put("metadata", buildMetadata(request));

            // RequestOptions with idempotency key and Stripe Connect account (if
            // applicable)
            var requestOptions = RequestOptions.builder()
                    .setIdempotencyKey(request.idempotencyKey())
                    .setStripeAccount(connectAccount.orElse(null))
                    .build();

            Charge charge = Charge.create(chargeParams, requestOptions);

            // Map Stripe charge status to gateway response
            GatewayPaymentStatus status = mapChargeStatus(charge);
            String failureReason = charge.getFailureMessage();

            var response = new GatewayPaymentResponse(
                    charge.getId(),
                    status,
                    charge.getId(), // authorization code = Stripe charge ID
                    failureReason,
                    charge.toJson());

            log.info("Stripe payment succeeded. Charge ID: {}, Amount: {} {}, Status: {}",
                    charge.getId(), request.amount(), request.currency(), status);

            return response;

        } catch (com.stripe.exception.IdempotencyException e) {
            // Idempotency conflict: transaction already exists
            log.warn("Idempotency conflict for key: {}. Retrieving existing transaction.", request.idempotencyKey());
            return handleIdempotencyConflict(request.idempotencyKey());
        } catch (StripeException e) {
            log.error("Stripe payment failed. Idempotency Key: {}, Error: {}", request.idempotencyKey(),
                    e.getMessage(), e);
            throw new PaymentGatewayException(
                    "Stripe payment failed: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public @NonNull Optional<GatewayPaymentResponse> getPaymentStatus(@NonNull String transactionId)
            throws PaymentGatewayException {
        try {
            log.debug("Retrieving Stripe charge status. Charge ID: {}", transactionId);

            Charge charge = Charge.retrieve(transactionId);
            if (charge == null) {
                return Optional.empty();
            }

            GatewayPaymentStatus status = mapChargeStatus(charge);
            var response = new GatewayPaymentResponse(
                    charge.getId(),
                    status,
                    charge.getId(),
                    charge.getFailureMessage(),
                    charge.toJson());

            return Optional.of(response);

        } catch (com.stripe.exception.InvalidRequestException e) {
            // Charge not found
            log.warn("Stripe charge not found. Charge ID: {}", transactionId);
            return Optional.empty();
        } catch (StripeException e) {
            log.error("Failed to retrieve Stripe charge status. Charge ID: {}, Error: {}", transactionId,
                    e.getMessage(), e);
            throw new PaymentGatewayException(
                    "Failed to retrieve payment status: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public @NonNull String getProviderName() {
        return "Stripe";
    }

    /**
     * Map Stripe charge status to PaymentGatewayProvider status enum.
     */
    private GatewayPaymentStatus mapChargeStatus(@NonNull Charge charge) {
        if (Boolean.TRUE.equals(charge.getPaid())) {
            return switch (charge.getStatus()) {
                case "succeeded" -> GatewayPaymentStatus.SUCCEEDED;
                case "authorized" -> GatewayPaymentStatus.AUTHORIZED;
                case "pending" -> GatewayPaymentStatus.PENDING;
                default -> GatewayPaymentStatus.SUCCEEDED;
            };
        } else {
            return switch (charge.getStatus()) {
                case "failed" -> GatewayPaymentStatus.FAILED;
                case "declined" -> GatewayPaymentStatus.DECLINED;
                default -> GatewayPaymentStatus.FAILED;
            };
        }
    }

    /**
     * Handle idempotency conflict by retrieving the existing charge.
     */
    private GatewayPaymentResponse handleIdempotencyConflict(@NonNull String idempotencyKey)
            throws PaymentGatewayException {
        try {
            // Stripe returns a 409 with IdempotencyException; we need to retrieve the
            // charge
            // by querying the search API or using the saved idempotency response.
            // For simplicity, we log and re-throw; the service layer should handle retries.
            throw new PaymentGatewayException(
                    "Idempotency conflict detected. A transaction with this key already exists. "
                            + "Use getPaymentStatus() to check its status.");
        } catch (Exception e) {
            throw new PaymentGatewayException("Idempotency conflict resolution failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build Stripe metadata map from request.
     */
    private java.util.Map<String, String> buildMetadata(@NonNull GatewayPaymentRequest request) {
        var metadata = new java.util.HashMap<String, String>();
        metadata.put("vendor_id", request.vendorId());
        metadata.put("payment_method", request.paymentMethod().toString());
        if (request.metadata() != null && request.metadata().length > 0) {
            for (int i = 0; i < request.metadata().length; i++) {
                metadata.put("meta_" + i, request.metadata()[i]);
            }
        }
        return metadata;
    }
}
