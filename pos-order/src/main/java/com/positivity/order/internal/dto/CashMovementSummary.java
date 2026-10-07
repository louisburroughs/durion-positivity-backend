package com.positivity.order.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
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
 * @param categoryCode petty-expense category, for PETTY_EXPENSE
 * @param vendorId the vendor paid, for VENDOR_COD
 * @param bagNumber deposit bag, for BANK_DROP
 * @param receiptReference receipt, for PETTY_EXPENSE
 * @param note optional free text (the whole free-text reason of an older movement)
 * @param clerkId cashier who recorded it, from the security context
 * @param approvedBy user id of the approving manager, or null
 * @param occurredAt when the movement was recorded
 */
public record CashMovementSummary(
        @NonNull UUID movementId,
        @NonNull UUID sessionId,
        @Nullable UUID requestId,
        @Nullable String reason,
        @NonNull String movementType,
        @NonNull BigDecimal amount,
        @Nullable String categoryCode,
        @Nullable UUID vendorId,
        @Nullable String bagNumber,
        @Nullable String receiptReference,
        @Nullable String note,
        @NonNull String clerkId,
        @Nullable String approvedBy,
        @NonNull Instant occurredAt) {}
