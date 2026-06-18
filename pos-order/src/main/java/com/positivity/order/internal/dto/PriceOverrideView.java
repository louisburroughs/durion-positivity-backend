package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.order.internal.entity.OverrideStatus;
import com.positivity.order.internal.entity.PriceOverrideReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Read view of a price override and its lifecycle state")
public class PriceOverrideView {

    @Schema(
            description = "Unique identifier of the price override",
            example = "01960003-0000-7000-8000-000000000100",
            requiredMode = REQUIRED)
    @NotNull
    private UUID overrideId;

    @Schema(
            description = "Identifier of the order whose line was overridden",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private String orderId;

    @Schema(
            description = "Identifier of the order line that received the override",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private String orderLineId;

    @Schema(
            description = "Identifier of the product on the overridden line",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private String productId;

    @Schema(description = "Original price of the line before the override", example = "49.99", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal originalPrice;

    @Schema(description = "Overridden price applied to the line", example = "39.99", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal overridePrice;

    @Schema(description = "Absolute discount amount from the override", example = "10.00", requiredMode = NOT_REQUIRED)
    private BigDecimal discountAmount;

    @Schema(
            description = "Discount expressed as a percentage of the original price",
            example = "20.00",
            requiredMode = NOT_REQUIRED)
    private BigDecimal discountPercentage;

    @Schema(description = "Reason code recorded for the override", example = "PRICE_MATCH", requiredMode = REQUIRED)
    @NotNull
    private PriceOverrideReasonCode reasonCode;

    @Schema(
            description = "Free-text justification supporting the override",
            example = "Competitor price match per store policy",
            requiredMode = NOT_REQUIRED)
    private String justification;

    @Schema(description = "Lifecycle status of the override", example = "APPROVED", requiredMode = REQUIRED)
    @NotNull
    private OverrideStatus status;

    @Schema(
            description = "Whether the override requires managerial approval before taking effect",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean requiresApproval;

    @Schema(
            description = "Identifier of the user who requested the override",
            example = "01960003-0000-7000-8000-000000000050",
            requiredMode = NOT_REQUIRED)
    private String requestedByUserId;

    @Schema(
            description = "Identifier of the user who approved the override",
            example = "01960003-0000-7000-8000-000000000051",
            requiredMode = NOT_REQUIRED)
    private String approvedByUserId;

    @Schema(
            description = "Identifier of the user who rejected the override",
            example = "01960003-0000-7000-8000-000000000052",
            requiredMode = NOT_REQUIRED)
    private String rejectedByUserId;

    @Schema(
            description = "Reason recorded when the override was rejected",
            example = "Outside approved discount threshold",
            requiredMode = NOT_REQUIRED)
    private String rejectionReason;

    @Schema(
            description = "Timestamp when the override was created (ISO 8601)",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the override was last updated (ISO 8601)",
            example = "2026-01-15T09:45:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;

    @Schema(
            description = "Timestamp when the override was approved (ISO 8601)",
            example = "2026-01-15T09:40:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant approvedAt;

    @Schema(
            description = "Timestamp when the override was rejected (ISO 8601)",
            example = "2026-01-15T09:50:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant rejectedAt;

    @Schema(
            description = "Timestamp when the override was applied to the order line (ISO 8601)",
            example = "2026-01-15T09:42:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant appliedAt;
}
