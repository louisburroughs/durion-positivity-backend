package com.positivity.accounting.internal.bankrec.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A bank account in reconciliation scope (SPEC §4.1, §6.1; story S2, #2301). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A reconcilable BANK_CASH account with its profile, baseline, frontiers and open counts")
public class BankAccountResponse {

    @Schema(description = "GL account id")
    private UUID glAccountId;

    @Schema(description = "GL account code", example = "1000")
    private String accountCode;

    @Schema(description = "GL account name", example = "Cash")
    private String accountName;

    @Schema(description = "Bank name from the profile; null until set", example = "First National")
    private String bankName;

    @Schema(description = "Last digits of the bank account number, display only", example = "4321")
    private String accountMask;

    @Schema(description = "Profile currency; null while the account has no profile", example = "USD")
    private String currency;

    @Schema(description = "Whether the account has a bank-account profile")
    private boolean profileExists;

    @Schema(
            description = "First day from which bank transactions and ledger lines must be explained; set only by"
                    + " committing an acknowledged statement; null before the first one",
            example = "2026-09-01")
    private LocalDate reconciliationBaselineDate;

    @Schema(description = "End date of the latest COMMITTED statement", example = "2026-09-30")
    private LocalDate coverageFrontier;

    @Schema(
            description = "End of the contiguous chain of FINALIZED reconciliations from the baseline",
            example = "2026-08-31")
    private LocalDate reconciledFrontier;

    @Schema(description = "UNMATCHED and POSSIBLE_DUPLICATE rows dated from the baseline on", example = "3")
    private Long unexplainedBankTransactionCount;

    @Schema(description = "OPEN outstanding items dated from the baseline on", example = "0")
    private Long openOutstandingItemCount;

    @Schema(description = "Bank-feed link state; NONE in phase 1", example = "NONE")
    private BankFeedLinkState feedLinkState;
}
