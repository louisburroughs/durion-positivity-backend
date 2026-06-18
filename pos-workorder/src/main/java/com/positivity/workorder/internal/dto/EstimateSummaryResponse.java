package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.enums.EstimateStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Customer-facing summary of an estimate with grouped line items and totals.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Customer-facing estimate summary with grouped items and totals")
public class EstimateSummaryResponse {

    @Schema(description = "Estimate identifier", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(description = "Estimate number", example = "EST-2026-1001", requiredMode = REQUIRED)
    @NotNull
    private String estimateNumber;

    @Schema(description = "Creation timestamp", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Estimate expiry timestamp", example = "2026-01-15T09:30:00", requiredMode = NOT_REQUIRED)
    private LocalDateTime expiresAt;

    @Schema(description = "Customer identifier", example = "550e8400-e29b-41d4-a716-446655440001", requiredMode = NOT_REQUIRED)
    private UUID customerId;

    @Schema(description = "Vehicle identifier", example = "550e8400-e29b-41d4-a716-446655440002", requiredMode = NOT_REQUIRED)
    private UUID vehicleId;

    @Schema(description = "Location identifier", example = "550e8400-e29b-41d4-a716-446655440003", requiredMode = NOT_REQUIRED)
    private UUID locationId;

    /** Current lifecycle status of the estimate. */
    @Schema(description = "Current estimate status", example = "DRAFT", requiredMode = REQUIRED)
    @NotNull
    private EstimateStatus status;

    // Grouped line items
    @Schema(description = "Part line items", requiredMode = NOT_REQUIRED)
    private List<EstimateItemResponse> partItems;

    @Schema(description = "Labor line items", requiredMode = NOT_REQUIRED)
    private List<EstimateItemResponse> laborItems;

    // Financial breakdown
    @Schema(description = "Subtotal amount", example = "150.00", requiredMode = NOT_REQUIRED)
    private BigDecimal subtotal;

    @Schema(description = "Tax amount", example = "12.38", requiredMode = NOT_REQUIRED)
    private BigDecimal taxAmount;

    @Schema(description = "Total amount", example = "162.38", requiredMode = NOT_REQUIRED)
    private BigDecimal total;

    @Schema(description = "Currency code", example = "USD", requiredMode = NOT_REQUIRED)
    private String currencyUomId;

    /**
     * Build summary from estimate and its items.
     */
    @NonNull
    public static EstimateSummaryResponse fromEstimateAndItems(
            @NonNull Estimate estimate, @NonNull List<EstimateItem> items) {

        List<EstimateItemResponse> partItems = items.stream()
                .filter(item -> item.getItemType() == com.positivity.workorder.internal.entity.EstimateItemType.PART)
                .map(EstimateItemResponse::fromEntity)
                .toList();

        List<EstimateItemResponse> laborItems = items.stream()
                .filter(item -> item.getItemType() == com.positivity.workorder.internal.entity.EstimateItemType.LABOR)
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
