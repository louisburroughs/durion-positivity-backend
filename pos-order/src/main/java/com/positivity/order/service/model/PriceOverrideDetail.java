package com.positivity.order.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Detailed view of a price override, safe to cross the service boundary.
 */
@Schema(description = "Detailed view of a price override including approval workflow state")
public record PriceOverrideDetail(
        @NotNull
                @Schema(
                        description = "Unique identifier of the price override",
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
                        description = "Current override status (e.g. APPROVED, PENDING_APPROVAL, REJECTED)",
                        example = "PENDING_APPROVAL",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String status,
        @NotNull
                @Schema(description = "Whether the override required approval", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
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
        @Schema(
                        description = "Identifier of the user who approved the override, if approved",
                        example = "manager-67890",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                String approvedByUserId,
        @Schema(
                        description = "Identifier of the user who rejected the override, if rejected",
                        example = "manager-67890",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                String rejectedByUserId,
        @Schema(
                        description = "Reason the override was rejected, if rejected",
                        example = "Discount exceeds allowed threshold",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                String rejectionReason,
        @NotNull
                @Schema(description = "Timestamp when the override was created", example = "2026-02-21T09:18:40Z", requiredMode = Schema.RequiredMode.REQUIRED)
                Instant createdAt,
        @Schema(
                        description = "Timestamp when the override was last updated",
                        example = "2026-02-21T10:05:12Z",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                Instant updatedAt,
        @Schema(
                        description = "Timestamp when the override was approved, if approved",
                        example = "2026-02-21T10:05:12Z",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                Instant approvedAt,
        @Schema(
                        description = "Timestamp when the override was rejected, if rejected",
                        example = "2026-02-21T10:05:12Z",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                Instant rejectedAt,
        @Schema(
                        description = "Timestamp when the override was applied to the line, if applied",
                        example = "2026-02-21T10:06:00Z",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                Instant appliedAt) {}
