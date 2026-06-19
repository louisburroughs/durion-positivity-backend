package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.entity.Estimate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    @Schema(
            description = "Estimate identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID estimateId;

    @Schema(description = "Human-readable estimate number", example = "EST-2026-1001", requiredMode = REQUIRED)
    @NotNull
    private String estimateNumber;

    @Schema(description = "Estimate lifecycle status", example = "DRAFT", requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Customer identifier",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID customerId;

    @Schema(
            description = "Vehicle identifier",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID vehicleId;

    @Schema(
            description = "Location identifier",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(description = "Currency code", example = "USD", requiredMode = NOT_REQUIRED)
    private String currencyUomId;

    @Schema(
            description = "Tax region identifier",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    private UUID taxRegionId;

    @Schema(
            description = "Actor identifier that created the estimate",
            example = "advisor@shop.local",
            requiredMode = NOT_REQUIRED)
    private String createdByUserId;

    @Schema(description = "Estimate creation timestamp", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    @NotNull
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
