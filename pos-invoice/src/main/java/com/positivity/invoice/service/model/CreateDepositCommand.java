package com.positivity.invoice.service.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Command to register a deposit credit taken by a pos-order sale (odoo-parity story E4, #1085).
 *
 * @param orderId the pos-order sale that took the deposit (idempotency anchor)
 * @param sourceType ESTIMATE / WORKORDER / ORDER the deposit is held against
 * @param sourceId source document id
 * @param partyId bill-to party, when known
 * @param amount deposit amount (positive)
 * @param currencyCode ISO-4217; defaults to USD when null/blank
 */
public record CreateDepositCommand(
        @NonNull UUID orderId,
        @NonNull String sourceType,
        @NonNull UUID sourceId,
        @Nullable String partyId,
        @NonNull BigDecimal amount,
        @Nullable String currencyCode) {

    public CreateDepositCommand {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
