package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A bank reconciliation header (SPEC-manual-bank-reconciliation §3.7, §6.1; story S4, #2303): the window,
 * every term of the explicit equation E3 and of the opening terms, the baseline and the unexplained
 * counts — computed live from the ledger, never from a snapshot. Lines are not embedded: the bank
 * transactions are read from {@code /bank-transactions}, and the review read model carries everything
 * else.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Bank reconciliation header with the live equation (E3), opening terms and unexplained counts")
public class BankReconciliationResponse {

    @Schema(description = "Reconciliation id")
    private UUID reconciliationId;

    @Schema(description = "Reconciled GL cash account id")
    private UUID glAccountId;

    @Schema(description = "Reconciled account code", example = "1000")
    private String accountCode;

    @Schema(description = "Reconciled account name", example = "Cash")
    private String accountName;

    @Schema(description = "The bank statement this reconciliation rests on")
    private UUID statementId;

    @Schema(description = "Statement window start date", example = "2026-09-01")
    private LocalDate statementStartDate;

    @Schema(description = "Statement window end date; the as-of date of every closing term", example = "2026-09-30")
    private LocalDate statementEndDate;

    @Schema(
            description = "YearMonth of the statement end date, for attribution only (never a window constraint)",
            example = "2026-09")
    private String accountingPeriodCode;

    @Schema(description = "Reconciliation currency (the ledger currency)", example = "USD")
    private String currency;

    @Schema(
            description = "Start date of the latest acknowledged statement on or before the window start: nothing"
                    + " dated before it counts as unexplained; null when the account has no acknowledged statement",
            example = "2026-09-01")
    private LocalDate baselineDate;

    @Schema(description = "Statement opening balance", example = "12000.0000")
    private BigDecimal statementOpeningBalance;

    @Schema(description = "Statement closing balance", example = "12345.6700")
    private BigDecimal statementClosingBalance;

    @Schema(description = "Σ ledger-side outstanding items open at the end of the window (deposits +, checks −)")
    private BigDecimal sumOutstandingLedgerItems;

    @Schema(description = "Σ bank-side outstanding items open at the end of the window (bank errors)")
    private BigDecimal sumOutstandingBankItems;

    @Schema(description = "statementClosingBalance + sumOutstandingLedgerItems − sumOutstandingBankItems")
    private BigDecimal adjustedBankBalance;

    @Schema(
            description = "Live ledger balance at the end of the statement end date, entries POSTED or REVERSED at"
                    + " their own dates")
    private BigDecimal glEndingBalance;

    @Schema(
            description = "Adjustment postings of this or an earlier reconciliation on the account dated after the"
                    + " statement end date")
    private BigDecimal sumLateAdjustments;

    @Schema(description = "glEndingBalance + sumLateAdjustments")
    private BigDecimal adjustedBookBalance;

    @Schema(description = "adjustedBankBalance − adjustedBookBalance; balanced within ±0.01", example = "0.0000")
    private BigDecimal difference;

    @Schema(description = "Live ledger balance at the end of the day before the window start")
    private BigDecimal glOpeningBalance;

    @Schema(
            description = "Adjustment postings of earlier windows, and this statement's gap bridges, dated on or after"
                    + " the window start")
    private BigDecimal sumOpeningAdjustments;

    @Schema(
            description = "Diagnostic only, never blocking: statementOpeningBalance + ledger items − bank items open at"
                    + " the day before the start − (glOpeningBalance + sumOpeningAdjustments)")
    private BigDecimal openingDifference;

    @Schema(description = "Unexplained bank transactions from the baseline to the window end (exact count)")
    private Integer countUnexplainedBank;

    @Schema(description = "Σ signed amounts of the unexplained bank transactions")
    private BigDecimal sumUnexplainedBank;

    @Schema(
            description = "Unexplained ledger lines from the baseline to the window end, aged OTHER_LEDGER_TIMING items"
                    + " not reaffirmed here included (exact count)")
    private Integer countUnexplainedLedger;

    @Schema(description = "Σ signed amounts of the unexplained ledger lines")
    private BigDecimal sumUnexplainedLedger;

    @Schema(description = "Reconciliation status", example = "IN_PROGRESS")
    private ReconciliationApiStatus status;

    @Schema(description = "Optimistic-lock version of the row", example = "3")
    private Long version;

    @Schema(description = "When the reconciliation was created")
    private Instant createdAt;

    @Schema(description = "Who created the reconciliation (the preparer)")
    private String createdBy;

    @Schema(description = "When the reconciliation was approved (finalized); null until then")
    private Instant finalizedAt;

    @Schema(description = "Who approved (finalized) the reconciliation")
    private String finalizedBy;

    @Schema(description = "When the preparer submitted it for approval; null while it is not submitted")
    private Instant submittedAt;

    @Schema(description = "Who submitted it for approval (the preparer)")
    private String submittedBy;

    @Schema(
            description = "The ledger balance as-of the statement end date that the approver saw, snapshotted at"
                    + " approval; compare with glEndingBalance (live) to see whether the ledger changed since",
            example = "12840.12")
    private BigDecimal approvedGlEndingBalance;

    @Schema(description = "When an approved reconciliation was invalidated")
    private Instant invalidatedAt;

    @Schema(
            description = "Why it was invalidated: LEDGER_LINE_REVERSED, LEDGER_LINE_POSTED, SOURCE_REMOVED or"
                    + " STATEMENT_SUPERSEDED",
            example = "LEDGER_LINE_REVERSED")
    private String invalidationReason;

    @Schema(description = "The journal entry whose posting or reversal invalidated it")
    private UUID invalidatedByJournalEntryId;

    @Schema(description = "The reconciliation this one supersedes (a correction of an approved window)")
    private UUID supersedesReconciliationId;

    @Schema(description = "The approved reconciliation that superseded this one")
    private UUID supersededByReconciliationId;

    @Schema(description = "When the reconciliation was cancelled")
    private Instant cancelledAt;

    @Schema(description = "Who cancelled it (the approver)")
    private String cancelledBy;

    @Schema(description = "Why it was cancelled")
    private String cancelReason;

    @Schema(description = "True when this answers a replayed create command (same requestId and payload)")
    private boolean replayed;

    /** The header of a reconciliation whose live terms were just stored on it. */
    public static BankReconciliationResponse from(BankReconciliation r) {
        return BankReconciliationResponse.builder()
                .reconciliationId(r.getReconciliationId())
                .glAccountId(r.getGlAccountId())
                .accountCode(r.getAccountCode())
                .accountName(r.getAccountName())
                .statementId(r.getStatementId())
                .statementStartDate(r.getStatementStartDate())
                .statementEndDate(r.getStatementEndDate())
                .accountingPeriodCode(r.getAccountingPeriodCode())
                .currency(r.getCurrency())
                .baselineDate(r.getBaselineDate())
                .statementOpeningBalance(r.getStatementOpeningBalance())
                .statementClosingBalance(r.getStatementClosingBalance())
                .sumOutstandingLedgerItems(r.getSumOutstandingLedgerItems())
                .sumOutstandingBankItems(r.getSumOutstandingBankItems())
                .adjustedBankBalance(r.getAdjustedBankBalance())
                .glEndingBalance(r.getGlEndingBalance())
                .sumLateAdjustments(r.getSumLateAdjustments())
                .adjustedBookBalance(r.getAdjustedBookBalance())
                .difference(r.getDifference())
                .glOpeningBalance(r.getGlOpeningBalance())
                .sumOpeningAdjustments(r.getSumOpeningAdjustments())
                .openingDifference(r.getOpeningDifference())
                .countUnexplainedBank(r.getCountUnexplainedBank())
                .sumUnexplainedBank(r.getSumUnexplainedBank())
                .countUnexplainedLedger(r.getCountUnexplainedLedger())
                .sumUnexplainedLedger(r.getSumUnexplainedLedger())
                .status(ReconciliationApiStatus.from(r.getStatus()))
                .version(r.getVersion())
                .createdAt(r.getCreatedAt())
                .createdBy(r.getCreatedBy())
                .finalizedAt(r.getFinalizedAt())
                .finalizedBy(r.getFinalizedBy())
                .submittedAt(r.getSubmittedAt())
                .submittedBy(r.getSubmittedBy())
                .approvedGlEndingBalance(r.getApprovedGlEndingBalance())
                .invalidatedAt(r.getInvalidatedAt())
                .invalidationReason(r.getInvalidationReason())
                .invalidatedByJournalEntryId(r.getInvalidatedByJournalEntryId())
                .supersedesReconciliationId(r.getSupersedesReconciliationId())
                .supersededByReconciliationId(r.getSupersededByReconciliationId())
                .cancelledAt(r.getCancelledAt())
                .cancelledBy(r.getCancelledBy())
                .cancelReason(r.getCancelReason())
                .build();
    }
}
