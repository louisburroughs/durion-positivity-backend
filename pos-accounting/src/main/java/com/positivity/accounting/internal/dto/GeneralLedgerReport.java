package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
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
 * General Ledger report response.
 *
 * Per-account chronological POSTED journal lines with running balance for a date
 * range, optionally filtered to a single account. Each account is rendered as a
 * {@link GeneralLedgerAccountSection} with opening balance, in-period lines, and
 * closing balance. Grand totals sum the in-period debit/credit activity across
 * all sections.
 *
 * Sections are empty when no POSTED activity exists in the range for the filter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "General Ledger report with per-account chronological POSTED lines and running balance")
public class GeneralLedgerReport {

    /**
     * Account filter echo. Null when the report spans all accounts.
     */
    @Schema(
            description = "Account ID the report was filtered to; null when spanning all accounts",
            example = "123e4567-e89b-12d3-a456-426614174000",
            requiredMode = NOT_REQUIRED)
    private String accountId;

    /**
     * Period start date (inclusive).
     */
    @Schema(description = "Period start date (inclusive)", example = "2026-01-01", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /**
     * Period end date (inclusive).
     */
    @Schema(description = "Period end date (inclusive)", example = "2026-06-30", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    /**
     * Timestamp when the report was generated.
     */
    @Schema(
            description = "Timestamp when the report was generated (ISO 8601)",
            example = "2026-06-30T08:00:00Z",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant generatedAt;

    /**
     * Per-account sections ordered by account number. Empty when no POSTED
     * activity exists in the range for the filter.
     */
    @Schema(
            description = "Per-account sections ordered by account number; empty when no POSTED activity exists",
            requiredMode = REQUIRED)
    @NonNull
    private List<GeneralLedgerAccountSection> accounts;

    /**
     * Grand total of in-period POSTED debit amounts across all sections.
     */
    @Schema(
            description = "Grand total of in-period POSTED debit amounts across all sections",
            example = "125000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalDebit;

    /**
     * Grand total of in-period POSTED credit amounts across all sections.
     */
    @Schema(
            description = "Grand total of in-period POSTED credit amounts across all sections",
            example = "125000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalCredit;
}
