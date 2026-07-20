package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.enums.BankAdjustmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A reconciliation adjustment and the JE it posted (Story F2, issue #965). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Reconciliation adjustment with its posted journal entry")
public class BankReconciliationAdjustmentResponse {

    @Schema(description = "Adjustment id")
    private UUID adjustmentId;

    @Schema(description = "Adjustment type (decision D-6)", example = "BANK_FEE")
    private BankAdjustmentType type;

    @Schema(description = "Signed adjustment amount", example = "-12.5000")
    private BigDecimal amount;

    @Schema(description = "Adjustment description")
    private String description;

    @Schema(description = "Id of the real balanced journal entry this adjustment posted")
    private UUID journalEntryId;

    @Schema(description = "When the adjustment was recorded")
    private Instant createdAt;

    @Schema(description = "Who recorded the adjustment")
    private String createdBy;

    public static BankReconciliationAdjustmentResponse from(BankReconciliationAdjustment adjustment) {
        return BankReconciliationAdjustmentResponse.builder()
                .adjustmentId(adjustment.getAdjustmentId())
                .type(adjustment.getAdjustmentType())
                .amount(adjustment.getAmount())
                .description(adjustment.getDescription())
                .journalEntryId(adjustment.getJournalEntryId())
                .createdAt(adjustment.getCreatedAt())
                .createdBy(adjustment.getCreatedBy())
                .build();
    }
}
