package com.positivity.order.internal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

import com.positivity.order.internal.entity.PriceOverrideReasonCode;

/**
 * Request DTO for applying a price override.
 */
@Data
public class ApplyPriceOverrideRequest {

    @NotBlank(message = "Order ID is required")
    private String orderId;

    @NotBlank(message = "Order line ID is required")
    private String orderLineId;

    @NotBlank(message = "Product ID is required")
    private String productId;

    @NotNull(message = "Original price is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Original price must be greater than 0")
    private BigDecimal originalPrice;

    @NotNull(message = "Override price is required")
    @DecimalMin(value = "0.0", message = "Override price must be non-negative")
    private BigDecimal overridePrice;

    @NotNull(message = "Reason code is required")
    private PriceOverrideReasonCode reasonCode;

    private String justification;
}
