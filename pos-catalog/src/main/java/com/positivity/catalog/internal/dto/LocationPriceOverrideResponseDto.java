package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.PriceOverrideStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
public class LocationPriceOverrideResponseDto {
    private UUID overrideId;
    private Long version;
    private UUID locationId;
    private UUID productId;
    private BigDecimal basePrice;
    private BigDecimal cost;
    private BigDecimal overridePrice;
    private BigDecimal discountPercent;
    private BigDecimal marginPercent;
    private PriceOverrideStatus status;
    private UUID createdByUserId;
    private Instant createdAt;
    private UUID assignedApproverId;
    private String assignmentStrategy;
    private UUID approvedByUserId;
    private Instant approvedAt;
    private UUID rejectedBy;
    private Instant rejectedAt;
    private String rejectionReasonCode;
    private String rejectionNotes;
}
