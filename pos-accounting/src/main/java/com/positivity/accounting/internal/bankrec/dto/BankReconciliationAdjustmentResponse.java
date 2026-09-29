package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A reconciliation adjustment and the journal entry it posted (Story F2 #965; S4 #2303 — SPEC §3.5). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Reconciliation adjustment with its posted journal entry, link and reversal state")
public class BankReconciliationAdjustmentResponse {

    @Schema(description = "Adjustment id")
    private UUID adjustmentId;

    @Schema(description = "The reconciliation that owns the adjustment")
    private UUID reconciliationId;

    @Schema(description = "Adjustment type", example = "BANK_FEE")
    private BankAdjustmentType type;

    @Schema(description = "Signed adjustment amount", example = "-12.5000")
    private BigDecimal amount;

    @Schema(description = "Adjustment description")
    private String description;

    @Schema(description = "Id of the balanced journal entry this adjustment posted")
    private UUID journalEntryId;

    @Schema(description = "Posted entry number", example = "JE-202609-0042")
    private String entryNumber;

    @Schema(description = "The date the entry posted at (D7)", example = "2026-09-15")
    private LocalDate transactionDate;

    @Schema(description = "YearMonth of the posting date", example = "2026-09")
    private String postedPeriodCode;

    @Schema(description = "POSTED or REVERSED")
    private AdjustmentStatus status;

    @Schema(description = "The bank transaction the adjustment explains")
    private UUID bankTransactionId;

    @Schema(description = "The match whose residual the adjustment settled")
    private UUID settlesMatchId;

    @Schema(description = "The statement whose acknowledged gap the adjustment bridges")
    private UUID bridgesStatementId;

    @Schema(description = "TRANSFER counter bank account")
    private UUID counterGlAccountId;

    @Schema(description = "Justification recorded for the adjustment")
    private String justification;

    @Schema(description = "Justification of a posting into a CLOSED period")
    private String overrideJustification;

    @Schema(description = "Whether the posting used the period override")
    private boolean periodOverride;

    @Schema(description = "The ADJUSTMENT match of a linked bank transaction, or a residual's replacement match")
    private UUID matchId;

    private UUID reversalJournalEntryId;
    private Instant reversedAt;
    private String reversedBy;
    private String reversalReason;

    @Schema(description = "When the adjustment was recorded")
    private Instant createdAt;

    @Schema(description = "Who recorded the adjustment")
    private String createdBy;

    @Schema(description = "True when this answers a replayed command (same requestId and payload)")
    private boolean replayed;

    public static BankReconciliationAdjustmentResponse from(BankReconciliationAdjustment a) {
        return BankReconciliationAdjustmentResponse.builder()
                .adjustmentId(a.getAdjustmentId())
                .reconciliationId(a.getReconciliationId())
                .type(a.getAdjustmentType())
                .amount(a.getAmount())
                .description(a.getDescription())
                .journalEntryId(a.getJournalEntryId())
                .transactionDate(a.getTransactionDate())
                .postedPeriodCode(a.getPostedPeriodCode())
                .status(a.getStatus())
                .bankTransactionId(a.getBankTransactionId())
                .settlesMatchId(a.getSettlesMatchId())
                .bridgesStatementId(a.getBridgesStatementId())
                .counterGlAccountId(a.getCounterGlAccountId())
                .justification(a.getJustification())
                .overrideJustification(a.getOverrideJustification())
                .periodOverride(a.getOverrideJustification() != null)
                .reversalJournalEntryId(a.getReversalJournalEntryId())
                .reversedAt(a.getReversedAt())
                .reversedBy(a.getReversedBy())
                .reversalReason(a.getReversalReason())
                .createdAt(a.getCreatedAt())
                .createdBy(a.getCreatedBy())
                .build();
    }
}
