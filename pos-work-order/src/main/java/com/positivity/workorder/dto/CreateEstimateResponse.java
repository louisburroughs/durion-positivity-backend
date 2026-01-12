package com.positivity.workorder.dto;

import com.positivity.workorder.entity.Estimate;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response containing the newly created draft estimate")
public class CreateEstimateResponse {
    
    @Schema(description = "System-generated unique identifier", example = "1")
    private Long estimateId;
    
    @Schema(description = "Human-readable unique estimate number", example = "EST-10021")
    private String estimateNumber;
    
    @Schema(description = "ID of the customer", example = "123")
    private Long customerId;
    
    @Schema(description = "ID of the vehicle", example = "456")
    private Long vehicleId;
    
    @Schema(description = "ID of the shop", example = "1")
    private Long shopId;
    
    @Schema(description = "Current status of the estimate", example = "DRAFT")
    private Estimate.EstimateStatus status;
    
    @Schema(description = "Timestamp when the estimate was created")
    private LocalDateTime createdAt;
    
    @Schema(description = "ID of the user who created the estimate", example = "789")
    private Long createdById;
    
    public static CreateEstimateResponse fromEntity(Estimate estimate) {
        return CreateEstimateResponse.builder()
                .estimateId(estimate.getId())
                .estimateNumber(estimate.getEstimateNumber())
                .customerId(estimate.getCustomerId())
                .vehicleId(estimate.getVehicleId())
                .shopId(estimate.getShopId())
                .status(estimate.getStatus())
                .createdAt(estimate.getCreatedAt())
                .createdById(estimate.getCreatedById())
                .build();
    }
}
