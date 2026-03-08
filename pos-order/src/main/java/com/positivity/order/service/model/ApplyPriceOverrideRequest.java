package com.positivity.order.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

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

        @Schema(description = "Reason code for the price override",
            example = "PRICE_MATCH", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String reasonCode;

    private String justification;

    /**
     * Optional idempotency key. If provided and a prior override with this
     * key exists, returns the existing override without creating a duplicate.
     */
    @Schema(description = "Optional idempotency key for duplicate prevention", example = "req-abc123", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String idempotencyKey;
}