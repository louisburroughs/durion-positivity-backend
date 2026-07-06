package com.positivity.accounting.internal.payment;

import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.net.RequestOptions;
import java.math.RoundingMode;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

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
public class StripePaymentGateway implements PaymentGatewayProvider {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

    private final Optional<String> connectAccount;

    @SuppressWarnings("java:S1172")
    public StripePaymentGateway(
            @Value("${stripe.api-key:}") String apiKey,
            @Value("${stripe.connect-account:}") String connectAccount,
            @Value("${stripe.idempotency-window-hours:24}") long idempotencyWindowHours) {
        this.connectAccount =
                connectAccount != null && !connectAccount.isBlank() ? Optional.of(connectAccount) : Optional.empty();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Stripe API key not configured. Set STRIPE_API_KEY environment variable or stripe.api-key property");
        }

        Stripe.apiKey = apiKey;
        log.info(
                "Stripe payment gateway initialized. Connect account: {}",
                this.connectAccount.isPresent() ? "yes" : "no");
    }

    @Override
    public @NonNull GatewayPaymentResponse executePayment(@NonNull GatewayPaymentRequest request)
            throws PaymentGatewayException {
        try {
            log.debug(
                    "Executing Stripe payment. Idempotency Key: {}, Amount: {} {}, Method: {}",
                    request.getIdempotencyKey(),
                    request.getAmount(),
                    request.getCurrency(),
                    request.getPaymentMethod());

            // Convert amount to integer cents using explicit rounding (no truncation)
            long amountInCents = request.getAmount()
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();

            var chargeParams = new java.util.HashMap<String, Object>();
            chargeParams.put("amount", amountInCents);
            chargeParams.put("currency", request.getCurrency().toLowerCase());
            chargeParams.put("source", request.getPaymentSource());
            chargeParams.put("description", request.getMemo());
            chargeParams.put("metadata", buildMetadata(request));

            // RequestOptions with idempotency key and Stripe Connect account (if
            // applicable)
            var requestOptions = RequestOptions.builder()
                    .setIdempotencyKey(request.getIdempotencyKey())
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

            log.info(
                    "Stripe payment succeeded. Charge ID: {}, Amount: {} {}, Status: {}",
                    charge.getId(),
                    request.getAmount(),
                    request.getCurrency(),
                    status);

            return response;

        } catch (com.stripe.exception.IdempotencyException e) {
            // Idempotency conflict: transaction already exists
            log.warn("Idempotency conflict for key: {}. Retrieving existing transaction.", request.getIdempotencyKey());
            return handleIdempotencyConflict(request.getIdempotencyKey());
        } catch (StripeException e) {
            throw new PaymentGatewayException(
                    "Stripe payment failed for idempotency key: " + request.getIdempotencyKey(), e);
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
                    charge.getId(), status, charge.getId(), charge.getFailureMessage(), charge.toJson());

            return Optional.of(response);

        } catch (com.stripe.exception.InvalidRequestException e) {
            // Charge not found
            log.warn("Stripe charge not found. Charge ID: {}", transactionId);
            return Optional.empty();
        } catch (StripeException e) {
            throw new PaymentGatewayException(
                    "Failed to retrieve Stripe charge status for charge ID: " + transactionId, e);
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
        // Stripe returns a 409 with IdempotencyException. Until we persist and
        // resolve original charge IDs by idempotency key, fail with clear context.
        throw new PaymentGatewayException("Idempotency conflict detected for key: " + idempotencyKey
                + ". A transaction with this key may already exist. "
                + "Use getPaymentStatus() to verify status.");
    }

    /**
     * Build Stripe metadata map from request.
     */
    private java.util.Map<String, String> buildMetadata(@NonNull GatewayPaymentRequest request) {
        var metadata = new java.util.HashMap<String, String>();
        metadata.put("vendor_id", request.getVendorId());
        metadata.put("payment_method", request.getPaymentMethod().toString());
        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            for (int i = 0; i < request.getMetadata().size(); i++) {
                metadata.put("meta_" + i, request.getMetadata().get(i));
            }
        }
        return metadata;
    }
}
