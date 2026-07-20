package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.entity.BankReconciliation;
import com.positivity.accounting.internal.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.entity.BankReconciliationLine;
import com.positivity.accounting.internal.enums.ReconciliationStatus;
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
 * A bank reconciliation with its imported statement lines and adjustments
 * (Story F2, issue #965).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Bank reconciliation with its statement lines and adjustments")
public class BankReconciliationResponse {

    @Schema(description = "Reconciliation id")
    private UUID reconciliationId;

    @Schema(description = "Reconciled GL cash account id")
    private UUID glAccountId;

    @Schema(description = "Reconciled account code", example = "1000")
    private String accountCode;

    @Schema(description = "Reconciled account name", example = "Cash")
    private String accountName;

    @Schema(description = "Statement period start date", example = "2026-06-01")
    private LocalDate periodStartDate;

    @Schema(description = "Statement period end date", example = "2026-06-30")
    private LocalDate periodEndDate;

    @Schema(description = "Statement date", example = "2026-06-30")
    private LocalDate statementDate;

    @Schema(description = "Reconciliation currency", example = "USD")
    private String currency;

    @Schema(description = "Statement ending balance", example = "12500.0000")
    private BigDecimal statementEndingBalance;

    @Schema(description = "GL ending balance snapshotted at import as-of the statement date", example = "12000.0000")
    private BigDecimal glEndingBalance;

    @Schema(
            description = "statementEndingBalance − (glEndingBalance + Σ adjustments); 0 when balanced",
            example = "0.0000")
    private BigDecimal difference;

    @Schema(description = "Reconciliation status", example = "IN_PROGRESS")
    private ReconciliationStatus status;

    @Schema(description = "When the reconciliation was created")
    private Instant createdAt;

    @Schema(description = "Who created the reconciliation")
    private String createdBy;

    @Schema(description = "When the reconciliation was finalized; null while IN_PROGRESS")
    private Instant finalizedAt;

    @Schema(description = "Who finalized the reconciliation")
    private String finalizedBy;

    @Schema(description = "Imported statement lines")
    private List<BankReconciliationLineResponse> statementLines;

    @Schema(description = "Recorded adjustments")
    private List<BankReconciliationAdjustmentResponse> adjustments;

    /** Map a reconciliation aggregate to its API response. */
    public static BankReconciliationResponse from(
            BankReconciliation r, List<BankReconciliationLine> lines, List<BankReconciliationAdjustment> adjustments) {
        return BankReconciliationResponse.builder()
                .reconciliationId(r.getReconciliationId())
                .glAccountId(r.getGlAccountId())
                .accountCode(r.getAccountCode())
                .accountName(r.getAccountName())
                .periodStartDate(r.getPeriodStartDate())
                .periodEndDate(r.getPeriodEndDate())
                .statementDate(r.getStatementDate())
                .currency(r.getCurrency())
                .statementEndingBalance(r.getStatementEndingBalance())
                .glEndingBalance(r.getGlEndingBalance())
                .difference(r.getDifference())
                .status(r.getStatus())
                .createdAt(r.getCreatedAt())
                .createdBy(r.getCreatedBy())
                .finalizedAt(r.getFinalizedAt())
                .finalizedBy(r.getFinalizedBy())
                .statementLines(
                        lines.stream().map(BankReconciliationLineResponse::from).toList())
                .adjustments(adjustments.stream()
                        .map(BankReconciliationAdjustmentResponse::from)
                        .toList())
                .build();
    }
}
