package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.Estimate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateEstimateResponse {
    private UUID estimateId;
    private String estimateNumber;
    private String status;
    private UUID customerId;
    private UUID vehicleId;
    private UUID locationId;
    private String currencyUomId;
    private UUID taxRegionId;
    private String createdByUserId;
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
