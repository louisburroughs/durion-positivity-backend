package com.positivity.order.internal.service.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Command to create a return against a completed order (parity story F1, #1086).
 *
 * @param originalOrderId the single COMPLETED order being returned against
 * @param refundMethod ORIGINAL_TENDER / STORE_CREDIT / ON_ACCOUNT_CREDIT
 * @param reasonCode return reason
 * @param lines requested return lines (non-empty)
 * @param idempotencyKey optional client key; replays return the original return order
 */
public record CreateReturnCommand(
        @NonNull UUID originalOrderId,
        @NonNull String refundMethod,
        @Nullable String reasonCode,
        @NonNull List<ReturnLineCommand> lines,
        @Nullable String idempotencyKey) {

    public CreateReturnCommand {
        Objects.requireNonNull(originalOrderId, "originalOrderId must not be null");
        Objects.requireNonNull(refundMethod, "refundMethod must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }
}
