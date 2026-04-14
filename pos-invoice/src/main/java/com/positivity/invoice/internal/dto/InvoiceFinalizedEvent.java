package com.positivity.invoice.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Event emitted when an invoice is finalized. Story #13 scaffold.
 *
 * @param invoiceId   Platform UUID of the finalized invoice.
 * @param workorderId Platform UUID of the associated workorder.
 * @param finalizedBy Username/actor who performed finalization (nullable if
 *                    system-triggered).
 * @param finalizedAt Timestamp when finalization occurred.
 * @param grandTotal  Total amount of the finalized invoice.
 */
public record InvoiceFinalizedEvent(
        @NonNull UUID invoiceId,
        @NonNull UUID workorderId,
        @Nullable String finalizedBy,
        @NonNull Instant finalizedAt,
        @NonNull BigDecimal grandTotal) {
    // Per ADR-0027: UUID for platform entity IDs; grandTotal as BigDecimal per
    // monetary convention.
}
