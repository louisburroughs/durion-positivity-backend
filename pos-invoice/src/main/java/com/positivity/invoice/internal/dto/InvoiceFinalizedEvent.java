package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "Event emitted when an invoice is finalized")
public record InvoiceFinalizedEvent(
        @NonNull
                @NotNull
                @Schema(
                        description = "Platform UUID of the finalized invoice",
                        example = "01960003-0000-7000-8000-000000000040",
                        requiredMode = REQUIRED)
                UUID invoiceId,
        @NonNull
                @NotNull
                @Schema(
                        description = "Platform UUID of the associated workorder",
                        example = "01960003-0000-7000-8000-000000000041",
                        requiredMode = REQUIRED)
                UUID workorderId,
        @Nullable
                @Schema(
                        description = "Username/actor who performed finalization (null if system-triggered)",
                        example = "jdoe",
                        requiredMode = NOT_REQUIRED)
                String finalizedBy,
        @NonNull
                @NotNull
                @Schema(
                        description = "Timestamp when finalization occurred",
                        example = "2026-01-16T12:00:00Z",
                        requiredMode = REQUIRED)
                Instant finalizedAt,
        @NonNull
                @NotNull
                @Schema(
                        description = "Total amount of the finalized invoice",
                        example = "191.00",
                        requiredMode = REQUIRED)
                BigDecimal grandTotal) {
    // Per ADR-0027: UUID for platform entity IDs; grandTotal as BigDecimal per
    // monetary convention.
}
