package com.positivity.order.internal.dto;

import com.positivity.order.internal.entity.OverrideStatus;
import com.positivity.order.internal.entity.PriceOverrideReasonCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Response DTO for price override application.
 */
@Data
@Builder
public class ApplyPriceOverrideResponse {

    private UUID overrideId;
    private String orderId;
    private String orderLineId;
    private String productId;
    private BigDecimal originalPrice;
    private BigDecimal overridePrice;
    private BigDecimal discountAmount;
    private BigDecimal discountPercentage;
    private PriceOverrideReasonCode reasonCode;
    private String justification;
    private OverrideStatus status;
    private Boolean requiresApproval;
    private String requestedByUserId;
    private Instant createdAt;
    private String message;
}
