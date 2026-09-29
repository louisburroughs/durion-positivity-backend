package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A bank statement keyed by hand (SPEC-manual-bank-reconciliation §4.3 item 3, §6.1; story S2,
 * #2301): the header and every transaction the preparer reads from the bank. The service validates
 * the shape itself so every refusal answers {@code VALIDATION_ERROR} with the field it names.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "A bank statement entered by hand: header, transactions and, when the statement does not"
                + " continue the previous one, a gap acknowledgement")
public class BankStatementCreateRequest {

    @Schema(description = "Reconcilable BANK_CASH GL account the statement belongs to", requiredMode = REQUIRED)
    private UUID glAccountId;

    @Schema(
            description = "Caller-generated UUIDv7; a replay with the same payload returns the original statement",
            requiredMode = REQUIRED)
    private UUID requestId;

    @Schema(description = "Statement header as printed by the bank", requiredMode = REQUIRED)
    private Header statement;

    @Schema(description = "Transactions of the statement, in the order the bank lists them", requiredMode = REQUIRED)
    private List<Transaction> transactions;

    @Schema(
            description = "ISO 4217 code of the amounts; defaults to the account profile's currency, and must equal it",
            example = "USD")
    private String currency;

    @Schema(
            description = "Justification (at least 10 characters) required when the statement does not continue"
                    + " the previous one — always for the account's first statement — and refused when it does",
            example = "Account opened at the new bank on 2026-09-01; earlier history is on the old statements")
    private String gapAcknowledgement;

    @Schema(
            description = "Start an IN_PROGRESS reconciliation of the committed statement in the same transaction;"
                    + " it appears in reconciliations (story S4)",
            example = "true")
    private Boolean startReconciliation;

    @Schema(
            description = "A COMMITTED statement of the same account this corrected statement supersedes (§4.9 path"
                    + " 3): it becomes SUPERSEDED, its rows EXCLUDED (STATEMENT_SUPERSEDED), and a FINALIZED"
                    + " reconciliation of it INVALIDATED; refused while it has an IN_PROGRESS or SUBMITTED"
                    + " reconciliation",
            example = "019a0000-0000-7000-8000-000000000002")
    private UUID supersedesStatementId;

    @Schema(
            description = "Why the statement is superseded (at least 10 characters); required with"
                    + " supersedesStatementId and refused without it",
            example = "The bank reissued September with the missing wire of 2026-09-14")
    private String supersessionJustification;

    /** The statement header. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "BankStatementHeaderRequest", description = "Statement header as printed by the bank")
    public static class Header {

        @Schema(description = "The bank's own statement number", example = "2026-09")
        private String statementRef;

        @Schema(description = "First day of the statement window", example = "2026-09-01", requiredMode = REQUIRED)
        private LocalDate startDate;

        @Schema(description = "Last day of the statement window", example = "2026-09-30", requiredMode = REQUIRED)
        private LocalDate endDate;

        @Schema(description = "Opening balance as printed", example = "12345.67", requiredMode = REQUIRED)
        private BigDecimal openingBalance;

        @Schema(description = "Closing balance as printed", example = "12840.12", requiredMode = REQUIRED)
        private BigDecimal closingBalance;
    }

    /** One keyed transaction: either {@code signedAmount}, or exactly one of {@code debit} and {@code credit}. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(
            name = "BankStatementTransactionRequest",
            description = "One statement transaction: signedAmount (positive = money into the account), or exactly"
                    + " one of debit (money out) and credit (money in)")
    public static class Transaction {

        @Schema(
                description = "Posting date; must lie inside the header window",
                example = "2026-09-02",
                requiredMode = REQUIRED)
        private LocalDate date;

        @Schema(description = "Signed amount, positive = money into the account; never zero", example = "500.00")
        private BigDecimal signedAmount;

        @Schema(description = "Money out of the account, as a positive number", example = "15.00")
        private BigDecimal debit;

        @Schema(description = "Money into the account, as a positive number", example = "500.00")
        private BigDecimal credit;

        @Schema(description = "Description as printed", example = "ACH DEPOSIT ACME", requiredMode = REQUIRED)
        private String description;

        @Schema(description = "The bank's reference", example = "DEP-1")
        private String reference;

        @Schema(description = "Check number", example = "1042")
        private String checkNumber;
    }
}
