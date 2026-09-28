package com.positivity.accounting.internal.bankrec.dto;

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
 * Reconciliation report (Story F2, issue #965): opening/closing balances, matched vs
 * outstanding lines, adjustments, and the outstanding difference.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Bank reconciliation report")
public class ReconciliationReportResponse {

    @Schema(description = "Reconciliation id")
    private UUID reconciliationId;

    @Schema(description = "Reconciled account code", example = "1000")
    private String accountCode;

    @Schema(description = "Reconciled account name", example = "Cash")
    private String accountName;

    @Schema(description = "Reconciliation currency", example = "USD")
    private String currency;

    @Schema(description = "Statement date", example = "2026-06-30")
    private LocalDate statementDate;

    @Schema(description = "Live GL balance at the end of the statement end date", example = "12000.0000")
    private BigDecimal glEndingBalance;

    @Schema(description = "Statement ending balance (closing basis)", example = "12500.0000")
    private BigDecimal statementEndingBalance;

    @Schema(description = "Sum of signed amounts over MATCHED statement lines", example = "500.0000")
    private BigDecimal totalMatched;

    @Schema(description = "Sum of signed adjustment amounts", example = "0.0000")
    private BigDecimal totalAdjustments;

    @Schema(description = "Sum of signed amounts over UNMATCHED statement lines", example = "0.0000")
    private BigDecimal totalOutstanding;

    @Schema(description = "Number of MATCHED statement lines", example = "5")
    private int matchedLineCount;

    @Schema(description = "Number of UNMATCHED statement lines", example = "0")
    private int outstandingLineCount;

    @Schema(description = "The live difference of E3: adjustedBankBalance − adjustedBookBalance", example = "0.0000")
    private BigDecimal difference;

    @Schema(description = "Recorded adjustments")
    private List<BankReconciliationAdjustmentResponse> adjustments;

    @Schema(description = "Outstanding (UNMATCHED) statement lines")
    private List<BankReconciliationLineResponse> outstandingLines;

    @Schema(description = "Every term of the explicit equation E3 (story S4, #2303)")
    private ReconciliationReviewResponse.Equation equation;

    @Schema(description = "The opening terms and the opening difference (diagnostic)")
    private ReconciliationReviewResponse.Diagnostics openingTerms;

    @Schema(description = "Outstanding items open at the window end, with their age")
    private List<OutstandingItemResponse> outstandingItems;

    @Schema(description = "Unexplained bank transactions from the baseline to the window end")
    private int countUnexplainedBank;

    private BigDecimal sumUnexplainedBank;

    @Schema(description = "Unexplained ledger lines from the baseline to the window end")
    private int countUnexplainedLedger;

    private BigDecimal sumUnexplainedLedger;

    @Schema(description = "Adjustments to clearing: every OTHER adjustment with link, justification, poster and age")
    private List<ReconciliationReviewResponse.ClearingAdjustment> adjustmentsToClearing;
}
