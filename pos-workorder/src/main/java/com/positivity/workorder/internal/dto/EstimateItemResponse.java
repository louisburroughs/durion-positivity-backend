package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Response DTO for estimate line items.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload for estimate line items")
public class EstimateItemResponse {

    @Schema(description = "Estimate item identifier", example = "550e8400-e29b-41d4-a716-446655440020")
    private UUID id;

    @Schema(description = "Parent estimate identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID estimateId;

    @Schema(description = "Estimate item type", example = "PART")
    private EstimateItemType itemType;

    @Nullable
    @Schema(description = "Item description", example = "Front brake pad replacement")
    private String description;

    @Schema(description = "Item quantity", example = "2")
    private BigDecimal quantity;

    @Schema(description = "Unit price", example = "59.99")
    private BigDecimal unitPrice;

    @Schema(description = "Computed line total", example = "119.98")
    private BigDecimal lineTotal;

    @Nullable
    @Schema(description = "Tax code", example = "TX-GENERAL")
    private String taxCode;

    @Nullable
    @Schema(
            description = "Referenced product identifier when itemType=PART",
            example = "550e8400-e29b-41d4-a716-446655440021")
    private UUID productId;

    @Nullable
    @Schema(
            description = "Referenced service identifier when itemType=LABOR",
            example = "550e8400-e29b-41d4-a716-446655440022")
    private UUID serviceId;

    @Schema(description = "Record creation timestamp")
    private LocalDateTime createdAt;

    @Schema(description = "Record last update timestamp")
    private LocalDateTime updatedAt;

    /**
     * Convert entity to response DTO.
     */
    @NonNull
    public static EstimateItemResponse fromEntity(@NonNull EstimateItem entity) {
        return EstimateItemResponse.builder()
                .id(entity.getId())
                .estimateId(entity.getEstimateId())
                .itemType(entity.getItemType())
                .description(entity.getDescription())
                .quantity(entity.getQuantity())
                .unitPrice(entity.getUnitPrice())
                .lineTotal(entity.getLineTotal())
                .taxCode(entity.getTaxCode())
                .productId(entity.getProductId())
                .serviceId(entity.getServiceId())
                .createdAt(LocalDateTime.ofInstant(entity.getCreatedAt(), ZoneOffset.UTC))
                .updatedAt(LocalDateTime.ofInstant(entity.getUpdatedAt(), ZoneOffset.UTC))
                .build();
    }
}
