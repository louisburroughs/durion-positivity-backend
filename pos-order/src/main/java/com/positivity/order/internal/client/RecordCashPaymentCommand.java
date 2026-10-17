package com.positivity.order.internal.client;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A register cash tender pos-order asks pos-invoice to record (ADR-0067 A8, #2662). pos-order has rounded the cash
 * at the register; pos-invoice records what it is sent, checks the triple and publishes the settled fact.
 *
 * @param idempotencyKey the tender's {@code Idempotency-Key}, replay-safe end to end
 * @param amount the amount applied to the invoice (the due before rounding)
 * @param settledAmount the cash that changed hands after rounding
 * @param cashRoundingAmount {@code settledAmount − amount}, signed
 * @param cashIncrement the increment applied, null when the currency has none
 * @param currencyCode the drawer's currency
 * @param sessionId the drawer that holds the cash
 * @param terminalId the register
 * @param cashierId the clerk who took the cash
 */
public record RecordCashPaymentCommand(
        @NonNull String idempotencyKey,
        @NonNull BigDecimal amount,
        @NonNull BigDecimal settledAmount,
        @NonNull BigDecimal cashRoundingAmount,
        @Nullable BigDecimal cashIncrement,
        @NonNull String currencyCode,
        @NonNull UUID sessionId,
        @Nullable String terminalId,
        @Nullable String cashierId) {}
