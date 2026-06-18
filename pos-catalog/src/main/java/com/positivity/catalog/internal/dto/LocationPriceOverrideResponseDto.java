package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.PriceOverrideStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Location price override detail")
public class LocationPriceOverrideResponseDto {

    @Schema(
            description = "Override identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID overrideId;

    @Schema(description = "Version for optimistic locking", example = "1", requiredMode = REQUIRED)
    private Long version;

    @Schema(
            description = "Location identifier the override applies to",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Product identifier the override applies to",
            example = "3d129775-efeb-57f0-b949-7c0ecf9f0c2e",
            requiredMode = REQUIRED)
    private UUID productId;

    @Schema(description = "Base price before override", example = "100.00", requiredMode = REQUIRED)
    private BigDecimal basePrice;

    @Schema(description = "Item cost used for margin calculation", example = "60.00", requiredMode = NOT_REQUIRED)
    private BigDecimal cost;

    @Schema(description = "Override price", example = "89.99", requiredMode = REQUIRED)
    private BigDecimal overridePrice;

    @Schema(description = "Computed discount percent versus base price", example = "10.01", requiredMode = NOT_REQUIRED)
    private BigDecimal discountPercent;

    @Schema(description = "Computed margin percent versus cost", example = "33.33", requiredMode = NOT_REQUIRED)
    private BigDecimal marginPercent;

    @Schema(
            description = "Current override status",
            example = "PENDING_APPROVAL",
            implementation = PriceOverrideStatus.class,
            requiredMode = REQUIRED)
    private PriceOverrideStatus status;

    @Schema(
            description = "Identifier of the user that created the override",
            example = "8b8df63e-18d8-4bde-a8f4-88bc36bc57d7",
            requiredMode = REQUIRED)
    private UUID createdByUserId;

    @Schema(description = "Timestamp the override was created", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Identifier of the assigned approver",
            example = "9c9ef74f-29e9-5cef-b9f5-99cd47cd68e8",
            requiredMode = NOT_REQUIRED)
    private UUID assignedApproverId;

    @Schema(description = "Strategy used to assign the approver", example = "ROUND_ROBIN", requiredMode = NOT_REQUIRED)
    private String assignmentStrategy;

    @Schema(
            description = "Identifier of the user that approved the override",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a540",
            requiredMode = NOT_REQUIRED)
    private UUID approvedByUserId;

    @Schema(description = "Timestamp the override was approved", example = "2026-01-15T10:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant approvedAt;

    @Schema(
            description = "Identifier of the user that rejected the override",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a541",
            requiredMode = NOT_REQUIRED)
    private UUID rejectedBy;

    @Schema(description = "Timestamp the override was rejected", example = "2026-01-15T10:05:00Z", requiredMode = NOT_REQUIRED)
    private Instant rejectedAt;

    @Schema(description = "Reason code for rejection", example = "BELOW_MARGIN", requiredMode = NOT_REQUIRED)
    private String rejectionReasonCode;

    @Schema(
            description = "Free-text notes for rejection",
            example = "Margin too low for this location",
            requiredMode = NOT_REQUIRED)
    private String rejectionNotes;
}
