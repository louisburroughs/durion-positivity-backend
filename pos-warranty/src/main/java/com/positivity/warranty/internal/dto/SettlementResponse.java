package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * API representation of an executed settlement (PRD §3.6). {@code claimStatus} reflects the
 * claim after the settlement ran, so the caller sees the {@code APPROVED → SETTLED} move without
 * a second read.
 */
@Schema(description = "Claim settlement — how the customer was made whole")
public record SettlementResponse(
        UUID id,
        UUID claimId,
        SettlementType settlementType,
        SettlementStatus status,
        UUID replacementWorkorderId,
        UUID invoiceId,
        UUID invoiceAdjustmentId,
        UUID refundRecordId,
        BigDecimal coveredAmount,
        BigDecimal customerAmount,

        @Schema(description = "Claim status after this settlement executed")
        ClaimStatus claimStatus,

        Instant createdAt,
        Instant updatedAt) {

    public static @NonNull SettlementResponse from(@NonNull ClaimSettlement entity, @NonNull ClaimStatus claimStatus) {
        return new SettlementResponse(
                entity.getId(),
                entity.getClaimId(),
                entity.getSettlementType(),
                entity.getStatus(),
                entity.getReplacementWorkorderId(),
                entity.getInvoiceId(),
                entity.getInvoiceAdjustmentId(),
                entity.getRefundRecordId(),
                entity.getCoveredAmount(),
                entity.getCustomerAmount(),
                claimStatus,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
