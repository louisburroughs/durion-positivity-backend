package com.positivity.accounting.internal.payment;

import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider.GatewayPaymentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic, stateless payment gateway for local development and demos (CAP:550 #2642), active when
 * {@code payment.gateway.provider=local}. The outcome is chosen by the suffix of the {@code paymentRef}:
 *
 * <ul>
 *   <li>{@code -PENDING}: PENDING until {@code payment.gateway.local.settle-after} (default {@code PT1M}) has elapsed
 *       since the charge, then SUCCEEDED;
 *   <li>{@code -DECLINE}: DECLINED, failure code {@code LOCAL_DECLINE};
 *   <li>{@code -RETURN}: SUCCEEDED on the charge, FAILED with failure code {@code LOCAL_RETURN} on a re-read after
 *       {@code settle-after} (an ACH-style return);
 *   <li>anything else: SUCCEEDED.
 * </ul>
 *
 * <p>No state is kept: the transaction id is {@code local_<paymentRef>_<epochMillis>}, so a re-read recovers the
 * reference and the charge instant from the id. {@link #findByReference} has no charge instant (a reference alone does
 * not carry one), so it answers the settled outcome: {@code -PENDING} SUCCEEDED, {@code -RETURN} FAILED. An id the
 * adapter did not mint (no {@code local_} prefix) is unknown: empty. The clock is the injected shared {@link Clock}
 * (ADR-0024).
 */
@Component
@ConditionalOnProperty(name = "payment.gateway.provider", havingValue = "local")
public class LocalPaymentGateway implements PaymentGatewayProvider {

    static final String ID_PREFIX = "local_";
    static final String PENDING_SUFFIX = "-PENDING";
    static final String DECLINE_SUFFIX = "-DECLINE";
    static final String RETURN_SUFFIX = "-RETURN";
    static final String DECLINE_CODE = "LOCAL_DECLINE";
    static final String RETURN_CODE = "LOCAL_RETURN";

    private final Clock clock;
    private final Duration settleAfter;

    public LocalPaymentGateway(
            Clock clock, @Value("${payment.gateway.local.settle-after:PT1M}") Duration settleAfter) {
        if (settleAfter == null || settleAfter.isNegative()) {
            throw new IllegalStateException("payment.gateway.local.settle-after must not be negative, was " + settleAfter);
        }
        this.clock = clock;
        this.settleAfter = settleAfter;
    }

    @Override
    public @NonNull GatewayPaymentResponse executePayment(@NonNull GatewayPaymentRequest request)
            throws PaymentGatewayException {
        String ref = request.getIdempotencyKey();
        Instant now = clock.instant();
        String id = ID_PREFIX + ref + "_" + now.toEpochMilli();
        GatewayPaymentStatus status;
        if (ref.endsWith(DECLINE_SUFFIX)) {
            status = GatewayPaymentStatus.DECLINED;
        } else if (ref.endsWith(PENDING_SUFFIX)) {
            status = GatewayPaymentStatus.PENDING;
        } else {
            status = GatewayPaymentStatus.SUCCEEDED;
        }
        return response(id, status, status == GatewayPaymentStatus.DECLINED ? DECLINE_CODE : null);
    }

    @Override
    public @NonNull Optional<GatewayPaymentResponse> getPaymentStatus(@NonNull String transactionId)
            throws PaymentGatewayException {
        if (!transactionId.startsWith(ID_PREFIX)) {
            return Optional.empty();
        }
        String body = transactionId.substring(ID_PREFIX.length());
        int cut = body.lastIndexOf('_');
        Long chargedAtMillis = cut > 0 ? parseMillis(body.substring(cut + 1)) : null;
        String ref = chargedAtMillis != null ? body.substring(0, cut) : body;
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        boolean settled = chargedAtMillis == null
                || !clock.instant().isBefore(Instant.ofEpochMilli(chargedAtMillis).plus(settleAfter));
        return Optional.of(outcome(transactionId, ref, settled));
    }

    @Override
    public @NonNull Optional<GatewayPaymentResponse> findByReference(@NonNull String paymentRef)
            throws PaymentGatewayException {
        String ref = paymentRef.startsWith(ID_PREFIX) ? paymentRef.substring(ID_PREFIX.length()) : paymentRef;
        if (ref.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(outcome(ID_PREFIX + ref, ref, true));
    }

    @Override
    public @NonNull String getProviderName() {
        return "local";
    }

    private GatewayPaymentResponse outcome(String transactionId, String ref, boolean settled) {
        if (ref.endsWith(DECLINE_SUFFIX)) {
            return response(transactionId, GatewayPaymentStatus.DECLINED, DECLINE_CODE);
        }
        if (ref.endsWith(PENDING_SUFFIX)) {
            return response(transactionId, settled ? GatewayPaymentStatus.SUCCEEDED : GatewayPaymentStatus.PENDING, null);
        }
        if (ref.endsWith(RETURN_SUFFIX) && settled) {
            return response(transactionId, GatewayPaymentStatus.FAILED, RETURN_CODE);
        }
        return response(transactionId, GatewayPaymentStatus.SUCCEEDED, null);
    }

    private static Long parseMillis(String digits) {
        if (digits.isEmpty() || digits.length() > 18 || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.valueOf(digits);
    }

    private static GatewayPaymentResponse response(String transactionId, GatewayPaymentStatus status, String failureCode) {
        return GatewayPaymentResponse.builder()
                .transactionId(transactionId)
                .status(status)
                .authorizationCode(transactionId)
                .failureReason(failureCode == null ? null : "Local gateway outcome " + failureCode)
                .failureCode(failureCode)
                .rawResponse("{\"provider\":\"local\",\"status\":\"" + status + "\"}")
                .build();
    }
}
