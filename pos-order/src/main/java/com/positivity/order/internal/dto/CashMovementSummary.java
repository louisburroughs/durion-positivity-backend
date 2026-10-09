package com.positivity.order.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read model of a drawer cash movement (parity story G1; fixed reasons from CAP:550 S16, #2512).
 *
 * @param movementId movement identifier
 * @param sessionId session the movement belongs to
 * @param requestId the register's idempotency key; null on a movement recorded before the fixed reasons
 * @param reason the fixed reason; null on a movement recorded before the fixed reasons
 * @param movementType PAID_IN or PAID_OUT, derived from the reason
 * @param amount positive cash amount moved
 * @param currencyCode ISO 4217 code of the amount; null on a movement recorded before the fixed reasons
 * @param categoryCode petty-expense category, for PETTY_EXPENSE
 * @param vendorId the vendor paid, for VENDOR_COD
 * @param bagNumber deposit bag, for BANK_DROP
 * @param receiptReference receipt, for PETTY_EXPENSE
 * @param note optional free text (the whole free-text reason of an older movement)
 * @param clerkId cashier who recorded it, from the security context (sign-in name)
 * @param clerkUserId the cashier's user id, when the sign-in carried one
 * @param approvedBy user id of the approving manager, or null
 * @param occurredAt when the movement was recorded
 * @param supplierName the supplier on a petty-expense receipt (CAP:550 S32d); CONFIDENTIAL
 * @param statedTaxes the tax the receipt states, per regime; empty when none
 * @param supplierRegistrationNumberProvided whether the supplier's number was recorded; the number itself never is
 *     part of a read
 * @param taxPlausibility {@code PLAUSIBLE} or {@code RATE_UNAVAILABLE}; null when no check was made
 * @param supplierRegistrationRequired whether an evidence rule asked for the number; null when no check was made
 */
public record CashMovementSummary(
        @NonNull UUID movementId,
        @NonNull UUID sessionId,
        @Nullable UUID requestId,
        @Nullable String reason,
        @NonNull String movementType,
        @NonNull BigDecimal amount,
        @Nullable String currencyCode,
        @Nullable String categoryCode,
        @Nullable UUID vendorId,
        @Nullable String bagNumber,
        @Nullable String receiptReference,
        @Nullable String note,
        @NonNull String clerkId,
        @Nullable UUID clerkUserId,
        @Nullable UUID approvedBy,
        @NonNull Instant occurredAt,
        @Nullable String supplierName,
        @NonNull List<CashMovementStatedTax> statedTaxes,
        boolean supplierRegistrationNumberProvided,
        @Nullable String taxPlausibility,
        @Nullable Boolean supplierRegistrationRequired) {

    /** Never prints the supplier's name. */
    @Override
    public String toString() {
        return "CashMovementSummary[movementId=" + movementId + ", sessionId=" + sessionId + ", reason=" + reason
                + ", amount=" + amount + ", currencyCode=" + currencyCode + ", categoryCode=" + categoryCode
                + ", statedTaxes=" + statedTaxes + ", supplierNameProvided=" + (supplierName != null)
                + ", supplierRegistrationNumberProvided=" + supplierRegistrationNumberProvided
                + ", taxPlausibility=" + taxPlausibility + "]";
    }
}
