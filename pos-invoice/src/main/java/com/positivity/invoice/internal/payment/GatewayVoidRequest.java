package com.positivity.invoice.internal.payment;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;

/**
 * Request payload for voiding an authorization remainder.
 * Story #9, AC3 (partial capture voiding).
 *
 * @param gatewayReference opaque reference from the prior authorization
 * @param voidedAmount     amount being voided (remainder after partial capture)
 */
public record GatewayVoidRequest(
        @NonNull String gatewayReference,
        @NonNull BigDecimal voidedAmount) {
}
