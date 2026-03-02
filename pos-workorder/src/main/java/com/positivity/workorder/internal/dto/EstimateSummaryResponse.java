package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

import com.positivity.workorder.internal.enums.EstimateStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Customer-facing summary of an estimate with grouped line items and totals.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EstimateSummaryResponse {

        private UUID id;
        private String estimateNumber;
        private Instant createdAt;
        private LocalDateTime expiresAt;
        private UUID customerId;
        private UUID vehicleId;
        private UUID locationId;

        /** Current lifecycle status of the estimate. */
        private EstimateStatus status;

        // Grouped line items
        private List<EstimateItemResponse> partItems;
        private List<EstimateItemResponse> laborItems;

        // Financial breakdown
        private BigDecimal subtotal;
        private BigDecimal taxAmount;
        private BigDecimal total;

        private String currencyUomId;

        /**
         * Build summary from estimate and its items.
         */
        @NonNull
        public static EstimateSummaryResponse fromEstimateAndItems(
                        @NonNull Estimate estimate,
                        @NonNull List<EstimateItem> items) {

                List<EstimateItemResponse> partItems = items.stream()
                                .filter(item -> item
                                                .getItemType() == com.positivity.workorder.internal.entity.EstimateItemType.PART)
                                .map(EstimateItemResponse::fromEntity)
                                .toList();

                List<EstimateItemResponse> laborItems = items.stream()
                                .filter(item -> item
                                                .getItemType() == com.positivity.workorder.internal.entity.EstimateItemType.LABOR)
                                .map(EstimateItemResponse::fromEntity)
                                .toList();

                return EstimateSummaryResponse.builder()
                                .id(estimate.getId())
                                .estimateNumber(estimate.getEstimateNumber())
                                .createdAt(estimate.getCreatedAt())
                                .expiresAt(estimate.getExpiresAt())
                                .customerId(estimate.getCustomerId())
                                .vehicleId(estimate.getVehicleId())
                                .locationId(estimate.getLocationId())
                                .status(estimate.getStatus())
                                .partItems(partItems)
                                .laborItems(laborItems)
                                .subtotal(estimate.getSubtotal())
                                .taxAmount(estimate.getTaxAmount())
                                .total(estimate.getTotal())
                                .currencyUomId(estimate.getCurrencyUomId())
                                .build();
        }
}
