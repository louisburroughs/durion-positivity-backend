package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * General Ledger section for a single account.
 *
 * Contains the account's opening balance (net POSTED activity strictly before
 * the report start date), the chronological in-period POSTED lines with running
 * balance, the period debit/credit totals, and the closing balance
 * ({@code openingBalance} plus in-period net activity).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "General Ledger section for a single account with opening/closing balances and lines")
public class GeneralLedgerAccountSection {

    /**
     * GL account ID (UUID).
     */
    @Schema(
            description = "GL account ID (UUID)",
            example = "123e4567-e89b-12d3-a456-426614174000",
            requiredMode = REQUIRED)
    @NonNull
    private String accountId;

    /**
     * GL account number (chart-of-accounts code).
     */
    @Schema(description = "GL account number (chart-of-accounts code)", example = "1000", requiredMode = REQUIRED)
    @NonNull
    private String accountNumber;

    /**
     * GL account display name.
     */
    @Schema(description = "GL account display name", example = "Cash - Operating", requiredMode = REQUIRED)
    @NonNull
    private String accountName;

    /**
     * Signed net balance (debit positive) of POSTED activity strictly before the
     * report start date.
     */
    @Schema(
            description = "Signed opening balance (debit positive) from POSTED activity before the start date",
            example = "50000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal openingBalance;

    /**
     * Chronological in-period POSTED lines, each carrying a running balance.
     */
    @Schema(description = "Chronological in-period POSTED lines with running balance", requiredMode = REQUIRED)
    @NonNull
    private List<GeneralLedgerLine> lines;

    /**
     * Sum of in-period POSTED debit amounts for the account.
     */
    @Schema(
            description = "Sum of in-period POSTED debit amounts for the account",
            example = "30000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalDebit;

    /**
     * Sum of in-period POSTED credit amounts for the account.
     */
    @Schema(
            description = "Sum of in-period POSTED credit amounts for the account",
            example = "0.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalCredit;

    /**
     * Signed closing balance (debit positive): openingBalance + in-period net.
     */
    @Schema(
            description = "Signed closing balance (debit positive): opening balance plus in-period net activity",
            example = "80000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal closingBalance;
}
