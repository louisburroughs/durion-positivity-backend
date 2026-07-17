package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.entity.VendorReimbursement;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** API representation of the back-office vendor-money lifecycle (PRD §3.7). */
@Schema(description = "Vendor reimbursement — back-office money lifecycle for a claim")
public record ReimbursementResponse(
        UUID id,
        UUID claimId,
        UUID providerId,
        ReimbursementStatus status,
        BigDecimal amountRequested,
        BigDecimal amountApproved,
        String vendorClaimReference,
        Instant submittedAt,
        String submittedBy,
        Instant resolvedAt,
        String creditReference,
        Instant creditReceivedAt,
        String notes,
        Instant createdAt,
        Instant updatedAt) {

    public static @NonNull ReimbursementResponse from(@NonNull VendorReimbursement entity) {
        return new ReimbursementResponse(
                entity.getId(),
                entity.getClaimId(),
                entity.getProviderId(),
                entity.getStatus(),
                entity.getAmountRequested(),
                entity.getAmountApproved(),
                entity.getVendorClaimReference(),
                entity.getSubmittedAt(),
                entity.getSubmittedBy(),
                entity.getResolvedAt(),
                entity.getCreditReference(),
                entity.getCreditReceivedAt(),
                entity.getNotes(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
