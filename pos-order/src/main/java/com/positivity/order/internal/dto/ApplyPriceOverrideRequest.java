package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

/**
 * Request DTO for applying a price override.
 *
 * @deprecated Use
 *             {@link com.positivity.order.service.model.ApplyPriceOverrideRequest}
 *             instead.
 */
@Deprecated(since = "2026-03")
@Data
@Schema(description = "Request payload for applying a price override to an order line")
public class ApplyPriceOverrideRequest {

    @Schema(
            description = "Identifier of the order whose line is being overridden",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotBlank(message = "Order ID is required")
    private String orderId;

    @Schema(
            description = "Identifier of the order line receiving the price override",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotBlank(message = "Order line ID is required")
    private String orderLineId;

    @Schema(
            description = "Identifier of the product on the order line",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotBlank(message = "Product ID is required")
    private String productId;

    @Schema(description = "Original price of the line before the override", example = "49.99", requiredMode = REQUIRED)
    @NotNull(message = "Original price is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Original price must be greater than 0")
    private BigDecimal originalPrice;

    @Schema(
            description = "Overridden price to apply to the line",
            example = "39.99",
            requiredMode = REQUIRED)
    @NotNull(message = "Override price is required")
    @DecimalMin(value = "0.0", message = "Override price must be non-negative")
    private BigDecimal overridePrice;

    @Schema(
            description = "Reason code for the price override",
            example = "PRICE_MATCH",
            requiredMode = REQUIRED)
    @NotBlank
    private String reasonCode;

    @Schema(
            description = "Free-text justification supporting the price override",
            example = "Competitor price match per store policy",
            requiredMode = NOT_REQUIRED)
    private String justification;

    /**
     * Optional idempotency key. If provided and a prior override with this
     * key exists, returns the existing override without creating a duplicate.
     */
    @Schema(
            description = "Optional idempotency key for duplicate prevention",
            example = "req-abc123",
            requiredMode = NOT_REQUIRED)
    private String idempotencyKey;
}
