package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for estimate responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response DTO for estimates")
public class EstimateResponse {

    @Schema(description = "Unique identifier for the estimate", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Estimate number", example = "EST-2024-1000")
    private String estimateNumber;

    @Schema(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID customerId;

    @Schema(description = "Vehicle ID", example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID vehicleId;

    @Schema(description = "Location ID", example = "550e8400-e29b-41d4-a716-446655440003")
    private UUID locationId;

    @Schema(description = "Currency UOM ID", example = "USD")
    private String currencyUomId;

    @Schema(description = "Tax region ID", example = "550e8400-e29b-41d4-a716-446655440004")
    private UUID taxRegionId;

    @Schema(description = "Estimate status", example = "DRAFT")
    private String status;

    @Schema(description = "User ID who created the estimate", example = "550e8400-e29b-41d4-a716-446655440005")
    private UUID createdByUserId;

    @Schema(description = "Date and time the estimate was created")
    private LocalDateTime createdAt;

    /**
     * Convert entity to response DTO
     */
    public static EstimateResponse fromEntity(Estimate entity) {
        if (entity == null) {
            return null;
        }

        return EstimateResponse.builder()
                .id(entity.getId())
                .estimateNumber(entity.getEstimateNumber())
                .customerId(entity.getCustomerId())
                .vehicleId(entity.getVehicleId())
                .locationId(entity.getLocationId())
                .currencyUomId(entity.getCurrencyUomId())
                .taxRegionId(entity.getTaxRegionId())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .createdByUserId(entity.getCreatedByUserId())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
