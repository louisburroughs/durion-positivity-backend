package com.positivity.invoice.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read model of a deposit credit and its application-audit trail (odoo-parity story E4, #1085).
 *
 * @param depositCreditId credit identifier
 * @param sourceType ESTIMATE / WORKORDER / ORDER
 * @param sourceId source document id the deposit was taken against
 * @param orderId the pos-order sale that took the deposit
 * @param partyId bill-to party, when known
 * @param currencyCode ISO-4217
 * @param originalAmount amount taken
 * @param remainingBalance amount still drawable
 * @param status AVAILABLE / PARTIALLY_APPLIED / FULLY_APPLIED / REFUNDED
 * @param applications draw-down audit rows
 * @param createdAt when the deposit was taken
 */
public record DepositCreditSummary(
        @NonNull UUID depositCreditId,
        @NonNull String sourceType,
        @NonNull UUID sourceId,
        @NonNull UUID orderId,
        @Nullable String partyId,
        @NonNull String currencyCode,
        @NonNull BigDecimal originalAmount,
        @NonNull BigDecimal remainingBalance,
        @NonNull String status,
        @NonNull List<Application> applications,
        @NonNull Instant createdAt) {

    /**
     * One draw-down against a settlement invoice.
     *
     * @param invoiceId invoice the credit was applied to
     * @param amountApplied amount drawn on this application
     * @param appliedAt when it was applied
     */
    public record Application(
            @NonNull UUID invoiceId,
            @NonNull BigDecimal amountApplied,
            @NonNull Instant appliedAt) {}
}
