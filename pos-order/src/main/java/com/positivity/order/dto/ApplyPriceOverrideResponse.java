package com.positivity.order.dto;

import com.positivity.order.model.OverrideStatus;
import com.positivity.order.model.PriceOverrideReasonCode;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for price override application.
 */
@Data
@Builder
public class ApplyPriceOverrideResponse {
    
    private Long overrideId;
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
