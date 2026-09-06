package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Bounds for one invoice revenue reconciliation run (#1851). Every field is optional; an empty body
 * reconciles every finalized invoice in the replica.
 */
@Schema(description = "Bounds for an invoice revenue reconciliation run; all fields optional")
public record InvoiceRevenueReconcileRequest(
        @Schema(description = "Only invoices finalized at or after this instant", example = "2026-07-01T00:00:00Z")
        @Nullable
        Instant finalizedFrom,

        @Schema(description = "Only invoices finalized before this instant", example = "2026-09-01T00:00:00Z") @Nullable
        Instant finalizedTo,

        @Schema(description = "Only these invoices (overrides the window when given)") @Nullable
        List<UUID> invoiceIds,

        @Schema(description = "Report what would be posted without posting", defaultValue = "false") @Nullable
        Boolean dryRun,

        @Schema(description = "Stop after this many candidates, oldest first (1-5000)", example = "500")
        @Nullable
        @Min(1)
        @Max(5000)
        Integer limit) {

    /** The request an empty body stands for: everything, live. */
    public static InvoiceRevenueReconcileRequest everything() {
        return new InvoiceRevenueReconcileRequest(null, null, null, null, null);
    }

    /** {@code dryRun} with an absent value read as {@code false}. */
    public boolean dryRunEnabled() {
        return Boolean.TRUE.equals(dryRun);
    }
}
