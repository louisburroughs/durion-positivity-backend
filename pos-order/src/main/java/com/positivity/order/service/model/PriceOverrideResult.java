package com.positivity.order.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Result from applying a price override.
 * status = APPROVED means applied immediately; PENDING_APPROVAL means queued
 * for review.
 */
@Schema(description = "Result of applying a price override to an order line")
public record PriceOverrideResult(
        @NotNull
                @Schema(
                        description = "Unique identifier of the created price override",
                        example = "01960003-0000-7000-8000-0000000000aa",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                UUID overrideId,
        @NotNull
                @Schema(
                        description = "Identifier of the order the override applies to",
                        example = "01960003-0000-7000-8000-000000000001",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String orderId,
        @NotNull
                @Schema(
                        description = "Identifier of the order line the override applies to",
                        example = "01960003-0000-7000-8000-000000000002",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String orderLineId,
        @NotNull
                @Schema(
                        description = "Identifier of the product on the overridden line",
                        example = "01960003-0000-7000-8000-000000000003",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String productId,
        @NotNull
                @Schema(description = "Original price before the override", example = "49.99", requiredMode = Schema.RequiredMode.REQUIRED)
                BigDecimal originalPrice,
        @NotNull
                @Schema(description = "Overridden price applied to the line", example = "39.99", requiredMode = Schema.RequiredMode.REQUIRED)
                BigDecimal overridePrice,
        @NotNull
                @Schema(description = "Absolute discount amount granted by the override", example = "10.00", requiredMode = Schema.RequiredMode.REQUIRED)
                BigDecimal discountAmount,
        @NotNull
                @Schema(description = "Discount expressed as a percentage of the original price", example = "20.00", requiredMode = Schema.RequiredMode.REQUIRED)
                BigDecimal discountPercentage,
        @NotNull
                @Schema(description = "Reason code supplied for the override", example = "PRICE_MATCH", requiredMode = Schema.RequiredMode.REQUIRED)
                String reasonCode,
        @Schema(
                        description = "Free-text justification supporting the override",
                        example = "Competitor price match per store policy",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                String justification,
        @NotNull
                @Schema(
                        description = "Override status; APPROVED means applied immediately, PENDING_APPROVAL means queued for review",
                        example = "APPROVED",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String status,
        @NotNull
                @Schema(description = "Whether the override required approval", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
                Boolean requiresApproval,
        @NotNull
                @Schema(description = "Whether the override affects sales commission", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
                Boolean affectsCommission,
        @NotNull
                @Schema(
                        description = "Identifier of the user who requested the override",
                        example = "user-12345",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String requestedByUserId,
        @NotNull
                @Schema(description = "Timestamp when the override was created", example = "2026-02-21T09:18:40Z", requiredMode = Schema.RequiredMode.REQUIRED)
                Instant createdAt,
        @Schema(
                        description = "Optional human-readable message describing the result",
                        example = "Price override applied",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                String message) {}
