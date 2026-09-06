package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Result of one invoice revenue reconciliation run (#1851): totals plus one outcome per candidate. */
@Schema(description = "Result of an invoice revenue reconciliation run")
public record InvoiceRevenueReconcileResponse(
        @Schema(description = "True when nothing was posted (dry run)")
        boolean dryRun,

        @Schema(description = "Candidates examined") int scanned,

        @Schema(description = "Postings created (or, on a dry run, that would be created)")
        int posted,

        @Schema(description = "Invoices whose revenue was already on the ledger")
        int alreadyPosted,

        @Schema(description = "Invoices skipped by rule (deposit-take, zero total, no finalizedAt, reversed cycle)")
        int skipped,

        @Schema(description = "Invoices whose posting raised an error")
        int failed,

        @Schema(description = "One entry per candidate, in the order examined")
        List<Outcome> outcomes) {

    /** What happened to one invoice. */
    @Schema(description = "Outcome for one invoice")
    public record Outcome(
            UUID invoiceId,
            @Nullable String invoiceNumber,
            @Nullable Instant finalizedAt,
            @Nullable BigDecimal total,

            @Schema(description = "POSTED, WOULD_POST, ALREADY_POSTED, SKIPPED or FAILED")
            Kind kind,

            @Schema(description = "Reason for a skip or the failure message; the journal entry id when posted")
            @Nullable
            String detail) {}

    /** Outcome classes. */
    public enum Kind {
        POSTED,
        WOULD_POST,
        ALREADY_POSTED,
        SKIPPED,
        FAILED
    }
}
