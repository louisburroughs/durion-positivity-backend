package com.positivity.invoice.internal.payment;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;

/**
 * Request payload for an explicit gateway capture operation.
 * Story #9, AC3.
 *
 * @param gatewayReference opaque reference from the prior authorization
 * @param captureAmount    amount to capture (may be partial)
 * @param idempotencyKey   client idempotency key for safe retry
 */
public record GatewayCaptureRequest(
        @NonNull String gatewayReference,
        @NonNull BigDecimal captureAmount,
        @NonNull String idempotencyKey) {
}
