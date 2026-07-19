package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * Trial Balance report response.
 *
 * Per-account debit/credit/balance rows from POSTED journal lines up to and
 * including the as-of date, with grand totals proving the balance constraint
 * (sum of debits == sum of credits), plus an entry-number gap-check footnote
 * block per monthly sequence scope.
 *
 * Rows are empty (with zero totals) when no POSTED data exists as of the
 * requested date. The gap footnote is empty on a clean ledger.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "Trial Balance report with per-account debit/credit/balance rows from POSTED journal lines, grand totals proving sum(debit) == sum(credit), and an entry-number gap-check footnote")
public class TrialBalanceReport {

    /**
     * Report as-of date (inclusive).
     */
    @Schema(
            description = "Date the trial balance is reported as of (inclusive)",
            example = "2026-06-30",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate asOfDate;

    /**
     * Timestamp when report was generated.
     */
    @Schema(
            description = "Timestamp when the report was generated (ISO 8601)",
            example = "2026-06-30T08:00:00Z",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant generatedAt;

    /**
     * Per-account rows ordered by account number. Empty when no POSTED data
     * exists as of the requested date.
     */
    @Schema(
            description = "Per-account trial balance rows ordered by account number; empty when no POSTED data exists",
            requiredMode = REQUIRED)
    @NonNull
    private List<TrialBalanceRow> rows;

    /**
     * Grand total of all debit amounts across rows.
     */
    @Schema(
            description = "Grand total of all POSTED debit amounts across rows",
            example = "1250000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalDebit;

    /**
     * Grand total of all credit amounts across rows.
     */
    @Schema(
            description = "Grand total of all POSTED credit amounts across rows",
            example = "1250000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalCredit;

    /**
     * Whether the trial balance balances: totalDebit == totalCredit.
     * FALSE surfaces a balance-constraint violation operationally.
     */
    @Schema(
            description = "Whether sum of debits equals sum of credits; false surfaces a balance-constraint violation",
            example = "true",
            requiredMode = REQUIRED)
    @NonNull
    private Boolean balanced;

    /**
     * Entry-number gap-check footnote block: one entry per monthly sequence
     * scope with missing numbers. Empty on a clean ledger.
     */
    @Schema(
            description =
                    "Entry-number gap-check footnote: per-month sequence scopes with missing entry numbers; empty on a clean ledger",
            requiredMode = REQUIRED)
    @NonNull
    private List<EntryNumberGapCheck> entryNumberGaps;
}
