package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.Workorder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for workorder responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response DTO for workorders")
public class WorkorderResponse {

    @Schema(description = "Unique identifier for the workorder", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Estimate ID", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID estimateId;

    @Schema(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID customerId;

    @Schema(description = "Shop ID", example = "550e8400-e29b-41d4-a716-446655440003")
    private UUID shopId;

    @Schema(description = "Vehicle ID", example = "550e8400-e29b-41d4-a716-446655440004")
    private UUID vehicleId;

    @Schema(description = "Workorder status", example = "DRAFT")
    private String status;

    @Schema(description = "Date and time the workorder was approved")
    private Instant approvedAt;

    @Schema(description = "Date and time the workorder was completed")
    private Instant completedAt;

    @Schema(description = "Whether the completed workorder is currently reopened for controlled edits")
    private Boolean isReopened;

    @Schema(description = "Date and time the workorder was reopened")
    private Instant reopenedAt;

    /**
     * Convert entity to response DTO
     */
    public static WorkorderResponse fromEntity(Workorder entity) {
        if (entity == null) {
            return null;
        }

        return WorkorderResponse.builder()
                .id(entity.getId())
                .estimateId(entity.getEstimateId())
                .customerId(entity.getCustomerId())
                .shopId(entity.getShopId())
                .vehicleId(entity.getVehicleId())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .approvedAt(entity.getApprovedAt())
                .completedAt(entity.getCompletedAt())
                .isReopened(entity.getIsReopened())
                .reopenedAt(entity.getReopenedAt())
                .build();
    }
}
