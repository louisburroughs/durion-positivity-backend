package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for estimate line items.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EstimateItemResponse {

    private UUID id;
    private UUID estimateId;
    private EstimateItemType itemType;
    @Nullable
    private String description;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;
    @Nullable
    private String taxCode;
    @Nullable
    private UUID productId;
    @Nullable
    private UUID serviceId;
    private LocalDateTime createdAt;
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
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
