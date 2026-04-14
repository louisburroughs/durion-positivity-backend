package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.Estimate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload for estimate creation")
public class CreateEstimateResponse {
    @Schema(description = "Estimate identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID estimateId;

    @Schema(description = "Human-readable estimate number", example = "EST-2026-1001")
    private String estimateNumber;

    @Schema(description = "Estimate lifecycle status", example = "DRAFT")
    private String status;

    @Schema(description = "Customer identifier", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID customerId;

    @Schema(description = "Vehicle identifier", example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID vehicleId;

    @Schema(description = "Location identifier", example = "550e8400-e29b-41d4-a716-446655440003")
    private UUID locationId;

    @Schema(description = "Currency code", example = "USD")
    private String currencyUomId;

    @Schema(description = "Tax region identifier", example = "550e8400-e29b-41d4-a716-446655440004")
    private UUID taxRegionId;

    @Schema(description = "Actor identifier that created the estimate", example = "advisor@shop.local")
    private String createdByUserId;

    @Schema(description = "Estimate creation timestamp", example = "2026-03-02T10:15:30Z")
    private Instant createdAt;

    public static CreateEstimateResponse fromEntity(Estimate estimate) {
        return CreateEstimateResponse.builder()
                .estimateId(estimate.getId())
                .estimateNumber(estimate.getEstimateNumber())
                .status(estimate.getStatus().name())
                .customerId(estimate.getCustomerId())
                .vehicleId(estimate.getVehicleId())
                .locationId(estimate.getLocationId())
                .currencyUomId(estimate.getCurrencyUomId())
                .taxRegionId(estimate.getTaxRegionId())
                .createdByUserId(estimate.getCreatedByUserId())
                .createdAt(estimate.getCreatedAt())
                .build();
    }
}
