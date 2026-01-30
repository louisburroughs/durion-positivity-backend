package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.Estimate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateEstimateResponse {
    private Long estimateId;
    private String estimateNumber;
    private String status;
    private Long customerId;
    private Long vehicleId;
    private Long locationId;
    private String currencyUomId;
    private Long taxRegionId;
    private Long createdByUserId;
    private LocalDateTime createdAt;
    
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
