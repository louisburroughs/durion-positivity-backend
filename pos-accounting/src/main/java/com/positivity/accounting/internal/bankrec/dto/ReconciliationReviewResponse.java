package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.ReadinessReason;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The review read model of a reconciliation (SPEC-manual-bank-reconciliation §4.8; story S4, #2303): one call,
 * no client arithmetic — the header, every term of E3 with its drill-down, the never-blocking opening
 * diagnostics, everything unresolved from the baseline on, the posted adjustments, the evidence and the
 * readiness.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "Reconciliation review: equation, diagnostics, unresolved items, adjustments, evidence, readiness")
public class ReconciliationReviewResponse {

    private Header header;
    private Equation equation;
    private Diagnostics diagnostics;
    private Unresolved unresolved;

    @Schema(
            description =
                    "Posted adjustments of this reconciliation, with entry number, date, period, override and reversal")
    private List<BankReconciliationAdjustmentResponse> adjustments;

    private Evidence evidence;
    private Readiness readiness;

    /** The reconciliation's identity, window, baseline, provenance and state. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Review header")
    public static class Header {
        private UUID reconciliationId;
        private UUID glAccountId;

        @Schema(example = "1000")
        private String accountCode;

        @Schema(example = "Cash")
        private String accountName;

        private UUID statementId;
        private LocalDate statementStartDate;
        private LocalDate statementEndDate;
        private String currency;

        @Schema(description = "The account baseline that applies to this window")
        private LocalDate baselineDate;

        @Schema(description = "Whether this window's statement set the baseline with its gap acknowledgement")
        private boolean baselineSetByThisStatement;

        @Schema(description = "The gap acknowledgement text of the statement that set the baseline")
        private String gapAcknowledgement;

        @Schema(description = "Statement provenance")
        private SourceKind sourceKind;

        private ReconciliationApiStatus status;

        @Schema(description = "The preparer (who created the reconciliation)")
        private String preparer;

        @Schema(description = "The approver (who finalized it); null until then")
        private String approver;

        @Schema(example = "2026-09")
        private String accountingPeriodCode;

        @Schema(description = "State of the period of the statement end date: OPEN, CLOSED or HARD_LOCKED")
        private String periodState;

        private Long version;
    }

    /** Every term of E3 with its drill-down. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "The explicit equation E3, term by term")
    public static class Equation {
        private BigDecimal statementClosingBalance;
        private List<OutstandingItemResponse> outstandingLedgerItems;
        private BigDecimal sumOutstandingLedgerItems;
        private List<OutstandingItemResponse> outstandingBankItems;
        private BigDecimal sumOutstandingBankItems;
        private BigDecimal adjustedBankBalance;
        private BigDecimal glEndingBalance;

        @Schema(description = "Adjustment postings dated after the window end, each with its owning reconciliation")
        private List<Posting> lateAdjustments;

        private BigDecimal sumLateAdjustments;
        private BigDecimal adjustedBookBalance;
        private BigDecimal difference;
    }

    /** The opening terms: a diagnostic, never a blocker. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Opening terms (diagnostic only)")
    public static class Diagnostics {
        private BigDecimal statementOpeningBalance;

        @Schema(description = "Σ ledger-side items open at the end of the day before the window")
        private BigDecimal openingLedgerItems;

        @Schema(description = "Σ bank-side items open at the end of the day before the window")
        private BigDecimal openingBankItems;

        private BigDecimal glOpeningBalance;
        private List<Posting> openingAdjustments;
        private BigDecimal sumOpeningAdjustments;
        private BigDecimal openingDifference;

        @Schema(description = "This statement's POSTED gap bridge, if any")
        private BankReconciliationAdjustmentResponse bridge;

        @Schema(description = "OPENING_DIFFERENCE when the opening difference is outside ±0.01")
        private List<String> flags;

        @Schema(
                description =
                        "Likely cause of an opening difference: GAP_NOT_BRIDGED, INVALIDATED_PREDECESSOR or UNKNOWN")
        private String likelyCause;
    }

    /** One adjustment posting on the account. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "An adjustment posting (the entry's or its reversal's cash line)")
    public static class Posting {
        private UUID adjustmentId;

        @Schema(description = "The reconciliation that owns the adjustment")
        private UUID reconciliationId;

        private UUID journalEntryId;
        private LocalDate date;
        private BigDecimal amount;

        @Schema(description = "Whether this is the reversal's line")
        private boolean reversal;
    }

    /** Everything unresolved from the baseline on. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Unresolved items, late arrivals first")
    public static class Unresolved {
        @Schema(description = "Late arrivals (phase 2 only), each with near-duplicate candidates")
        private List<BankRow> lateArrivals;

        @Schema(description = "Unexplained bank transactions with their top ledger candidate")
        private List<BankRow> unexplainedBank;

        @Schema(description = "Unexplained ledger lines with their top bank candidate")
        private List<LedgerRow> unexplainedLedger;

        @Schema(description = "POSSIBLE_DUPLICATE rows awaiting review, each with its near-duplicate candidates")
        private List<BankRow> possibleDuplicates;

        @Schema(description = "Aged OTHER_LEDGER_TIMING items awaiting reaffirmation in this reconciliation")
        private List<OutstandingItemResponse> agedItemsAwaitingReaffirmation;

        private List<ReconciliationMatchResponse> proposedMatches;
        private List<ReconciliationMatchResponse> brokenMatches;
    }

    /** A bank transaction as the review shows it. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Bank transaction row")
    public static class BankRow {
        private UUID bankTransactionId;
        private LocalDate transactionDate;
        private BigDecimal signedAmount;
        private String description;
        private String reference;
        private String checkNumber;
        private BankTransactionStatus status;
        private boolean arrivedAfterApproval;
        private Instant firstObservedAt;

        @Schema(description = "The best ledger candidate, if any")
        private ReconciliationCandidatesResponse.Candidate topCandidate;

        @Schema(description = "Near-duplicate candidates (candidate originals), best first, at most five")
        private List<BankRow> nearDuplicates;
    }

    /** A ledger line as the review shows it. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Ledger line row")
    public static class LedgerRow {
        private UUID glLineId;
        private UUID journalEntryId;
        private String entryNumber;
        private LocalDate date;
        private BigDecimal signedAmount;
        private String description;

        @Schema(description = "The best bank candidate, if any")
        private ReconciliationCandidatesResponse.Candidate topCandidate;
    }

    /** What the reconciliation rests on. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Evidence")
    public static class Evidence {
        @Schema(description = "Every match of this reconciliation, history included")
        private List<ReconciliationMatchResponse> matches;

        @Schema(description = "Outstanding items registered in this reconciliation, with age and last reaffirmation")
        private List<OutstandingItemResponse> outstandingItems;

        @Schema(description = "Bank transactions excluded, dated from the baseline to the window end")
        private List<BankRow> exclusions;

        @Schema(
                description =
                        "Adjustments to clearing: every OTHER adjustment with its link, justification, poster and age")
        private List<ClearingAdjustment> adjustmentsToClearing;

        private StatementSummary statement;
    }

    /** An OTHER adjustment to the clearing account. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Adjustment to clearing")
    public static class ClearingAdjustment {
        private UUID adjustmentId;
        private UUID journalEntryId;
        private BigDecimal amount;
        private LocalDate transactionDate;

        @Schema(description = "BANK_TRANSACTION, RESIDUAL_SETTLEMENT or GAP_BRIDGE")
        private String linkKind;

        private UUID linkId;
        private String justification;

        @Schema(description = "Who posted it")
        private String postedBy;

        @Schema(description = "Days from the posting date to the window end")
        private long ageDays;

        private String status;
    }

    /** The statement header. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Statement header")
    public static class StatementSummary {
        private UUID statementId;
        private SourceKind sourceKind;
        private String statementRef;
        private LocalDate startDate;
        private LocalDate endDate;
        private BigDecimal openingBalance;
        private BigDecimal closingBalance;
        private String gapAcknowledgement;
    }

    /** Whether the reconciliation can be submitted or approved, and why not. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Readiness (E4)")
    public static class Readiness {
        private boolean canSubmit;
        private boolean canApprove;

        @Schema(description = "Blocking reasons: NOT_BALANCED, UNEXPLAINED_BANK, UNEXPLAINED_LEDGER")
        private List<ReadinessReason> reasons;

        @Schema(description = "PROPOSED matches await acceptance (their rows are already unexplained)")
        private boolean proposalsPending;

        private int countUnexplainedBank;
        private BigDecimal sumUnexplainedBank;
        private int countUnexplainedLedger;
        private BigDecimal sumUnexplainedLedger;
    }
}
