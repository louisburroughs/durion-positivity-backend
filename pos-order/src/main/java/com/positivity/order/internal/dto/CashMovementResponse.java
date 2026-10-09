package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A drawer cash movement")
public record CashMovementResponse(
        @Schema(description = "Movement id") UUID movementId,

        @Schema(description = "Session the movement belongs to")
        UUID sessionId,

        @Schema(description = "The register's idempotency key; null on a movement recorded before the fixed reasons")
        UUID requestId,

        @Schema(
                description = "The fixed reason; null on a movement recorded before the fixed reasons",
                example = "PETTY_EXPENSE",
                allowableValues = {"PETTY_EXPENSE", "VENDOR_COD", "BANK_DROP", "FLOAT_INCREASE", "FLOAT_DECREASE"})
        String reason,

        @Schema(
                description = "Direction, derived from the reason",
                example = "PAID_OUT",
                allowableValues = {"PAID_IN", "PAID_OUT"})
        String movementType,

        @Schema(description = "Positive amount moved", example = "30.00")
        BigDecimal amount,

        @Schema(description = "ISO 4217 code of the amount (the functional currency)", example = "USD")
        String currencyCode,

        @Schema(description = "Petty-expense category, for PETTY_EXPENSE", example = "SHOP_SUPPLIES")
        String categoryCode,

        @Schema(description = "Vendor paid, for VENDOR_COD") UUID vendorId,

        @Schema(description = "Deposit bag, for BANK_DROP", example = "BAG-0042")
        String bagNumber,

        @Schema(description = "Receipt, for PETTY_EXPENSE", example = "R-1001")
        String receiptReference,

        @Schema(description = "Free-text note (the whole free-text reason of an older movement)")
        String note,

        @Schema(description = "Cashier who recorded it, from the security context (sign-in name)")
        String clerkId,

        @Schema(description = "The cashier's user id, when the sign-in carried one")
        UUID clerkUserId,

        @Schema(description = "User id of the approving manager, or null")
        UUID approvedBy,

        @Schema(description = "When the movement was recorded")
        Instant occurredAt,

        @Schema(description = "Supplier on a petty-expense receipt, or null", example = "Corner Hardware")
        String supplierName,

        @Schema(description = "Tax the receipt states, one entry per regime; empty when none")
        List<CashMovementStatedTax> statedTaxes,

        @Schema(
                description = "Whether the supplier's registration number was recorded; the number itself is never"
                        + " returned",
                example = "true")
        boolean supplierRegistrationNumberProvided,

        @Schema(
                description = "pos-tax's answer on the stated tax; null when no check was made",
                example = "PLAUSIBLE",
                allowableValues = {"PLAUSIBLE", "RATE_UNAVAILABLE"},
                nullable = true)
        String taxPlausibility,

        @Schema(
                description = "Whether an evidence rule asked for the supplier's number; null when no check was made",
                example = "false",
                nullable = true)
        Boolean supplierRegistrationRequired) {

    /** Never prints the supplier's name. */
    @Override
    public String toString() {
        return "CashMovementResponse[movementId=" + movementId + ", reason=" + reason + ", amount=" + amount
                + ", statedTaxes=" + statedTaxes + ", supplierNameProvided=" + (supplierName != null)
                + ", supplierRegistrationNumberProvided=" + supplierRegistrationNumberProvided + "]";
    }

    public static CashMovementResponse from(CashMovementSummary m) {
        return new CashMovementResponse(
                m.movementId(),
                m.sessionId(),
                m.requestId(),
                m.reason(),
                m.movementType(),
                m.amount(),
                m.currencyCode(),
                m.categoryCode(),
                m.vendorId(),
                m.bagNumber(),
                m.receiptReference(),
                m.note(),
                m.clerkId(),
                m.clerkUserId(),
                m.approvedBy(),
                m.occurredAt(),
                m.supplierName(),
                m.statedTaxes(),
                m.supplierRegistrationNumberProvided(),
                m.taxPlausibility(),
                m.supplierRegistrationRequired());
    }
}
