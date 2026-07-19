package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * One per-account row of the Trial Balance report.
 *
 * Debit and credit totals are aggregated from POSTED journal lines only.
 * Balance is the signed net of the account (totalDebit - totalCredit).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Per-account trial balance row aggregated from POSTED journal lines")
public class TrialBalanceRow {

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
     * Sum of POSTED debit amounts for the account up to the as-of date.
     */
    @Schema(
            description = "Sum of POSTED debit amounts for the account up to the as-of date",
            example = "125000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalDebit;

    /**
     * Sum of POSTED credit amounts for the account up to the as-of date.
     */
    @Schema(
            description = "Sum of POSTED credit amounts for the account up to the as-of date",
            example = "45000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalCredit;

    /**
     * Net balance for the account: totalDebit - totalCredit (signed).
     */
    @Schema(
            description = "Net account balance (totalDebit - totalCredit, signed)",
            example = "80000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal balance;
}
