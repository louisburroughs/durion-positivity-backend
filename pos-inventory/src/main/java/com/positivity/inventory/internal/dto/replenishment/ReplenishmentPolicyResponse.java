package com.positivity.inventory.internal.dto.replenishment;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "Representation of a replenishment policy defining min/max stock thresholds for an item at a location")
public class ReplenishmentPolicyResponse {

    @Schema(
            description = "Unique identifier of the replenishment policy",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private String policyId;

    @Schema(
            description = "Identifier of the location the replenishment policy applies to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "SKU of the item the replenishment policy applies to",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String itemSKU;

    @Schema(
            description = "Minimum on-hand quantity that triggers replenishment when reached",
            example = "5",
            requiredMode = REQUIRED)
    private int minimumQuantity;

    @Schema(
            description = "Maximum on-hand quantity replenishment aims to restock up to",
            example = "50",
            requiredMode = REQUIRED)
    private int maximumQuantity;

    @Schema(
            description = "Round the computed replenishment quantity up to the nearest multiple of this value;"
                    + " absent means no rounding",
            example = "6",
            requiredMode = NOT_REQUIRED)
    private Integer orderMultiple;

    @Schema(
            description = "Per-policy lead-time override in days; absent means vendor-feed/default lead time applies",
            example = "3",
            requiredMode = NOT_REQUIRED)
    private Integer leadTimeDaysOverride;

    @Schema(
            description = "Preferred sourcing channel for replenishing this policy's pick face",
            example = "EITHER",
            requiredMode = REQUIRED)
    private String preferredSourceType;

    @Schema(
            description = "Instant until which the policy is snoozed (excluded from replenishment evaluation);"
                    + " absent or past means the policy is evaluated normally",
            example = "2026-02-01T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    private String snoozedUntil;

    @Schema(
            description = "Whether the policy participates in replenishment evaluation",
            example = "true",
            requiredMode = REQUIRED)
    private boolean active;

    @Schema(
            description = "Timestamp at which the replenishment policy was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private String createdAt;
}
