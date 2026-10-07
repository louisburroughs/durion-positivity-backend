package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
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

        @Schema(description = "Petty-expense category, for PETTY_EXPENSE", example = "SHOP_SUPPLIES")
        String categoryCode,

        @Schema(description = "Vendor paid, for VENDOR_COD") UUID vendorId,

        @Schema(description = "Deposit bag, for BANK_DROP", example = "BAG-0042")
        String bagNumber,

        @Schema(description = "Receipt, for PETTY_EXPENSE", example = "R-1001")
        String receiptReference,

        @Schema(description = "Free-text note (the whole free-text reason of an older movement)")
        String note,

        @Schema(description = "Cashier who recorded it, from the security context")
        String clerkId,

        @Schema(description = "User id of the approving manager, or null")
        String approvedBy,

        @Schema(description = "When the movement was recorded")
        Instant occurredAt) {

    public static CashMovementResponse from(CashMovementSummary m) {
        return new CashMovementResponse(
                m.movementId(),
                m.sessionId(),
                m.requestId(),
                m.reason(),
                m.movementType(),
                m.amount(),
                m.categoryCode(),
                m.vendorId(),
                m.bagNumber(),
                m.receiptReference(),
                m.note(),
                m.clerkId(),
                m.approvedBy(),
                m.occurredAt());
    }
}
