package com.positivity.invoice.internal.payment;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request payload for gateway authorization and sale-capture operations.
 * Story #9.
 *
 * @param amount         payment amount (must be positive)
 * @param paymentToken   tokenised card reference; no PAN stored (AC8)
 * @param idempotencyKey client idempotency key for safe retry
 * @param invoiceId      invoice being paid
 */
public record PaymentGatewayRequest(
        @NonNull BigDecimal amount,
        @NonNull String paymentToken,
        @NonNull String idempotencyKey,
        @NonNull UUID invoiceId) {
}
