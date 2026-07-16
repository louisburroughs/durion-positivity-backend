package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.entity.PartReturn;
import com.positivity.warranty.internal.enums.PartReturnDisposition;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** API representation of a defective-part RMA record (PRD §3.8). */
@Schema(description = "Part return (RMA) — defective-part return lifecycle for a claim line")
public record PartReturnResponse(
        UUID id,
        UUID claimId,
        UUID claimLineId,
        String rmaNumber,
        PartReturnDisposition disposition,
        PartReturnStatus status,
        String carrier,
        String trackingNumber,
        Instant shippedAt,
        String holdLocationNote,
        Instant createdAt,
        Instant updatedAt) {

    public static @NonNull PartReturnResponse from(@NonNull PartReturn entity) {
        return new PartReturnResponse(
                entity.getId(),
                entity.getClaimId(),
                entity.getClaimLineId(),
                entity.getRmaNumber(),
                entity.getDisposition(),
                entity.getStatus(),
                entity.getCarrier(),
                entity.getTrackingNumber(),
                entity.getShippedAt(),
                entity.getHoldLocationNote(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
