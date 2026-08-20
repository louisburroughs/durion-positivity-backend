package com.positivity.inventory.internal.dto.replenishment;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the side-effect-free replenishment needs report (odoo-parity F3/F6, issue
 * #1041): the current orderpoint evaluation of an active, non-snoozed policy — exactly the
 * math the batch scan would apply, without creating or refreshing any task.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Current computed replenishment state of one active, non-snoozed policy: the same orderpoint"
                + " evaluation the batch scan applies, with no side effects")
public class ReplenishmentNeedResponse {

    @Schema(
            description = "Unique identifier of the replenishment policy",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private String policyId;

    @Schema(description = "SKU of the item the policy applies to", example = "SKU-10042", requiredMode = REQUIRED)
    @NotNull
    private String itemSKU;

    @Schema(
            description = "Identifier of the location the policy applies to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(description = "Current on-hand quantity at the policy's location", example = "3", requiredMode = REQUIRED)
    private BigDecimal onHand;

    @Schema(
            description = "Projected available quantity at the lead-time horizon"
                    + " (on-hand + expected supply - open demand)",
            example = "3",
            requiredMode = REQUIRED)
    private BigDecimal projectedAvailable;

    @Schema(
            description = "UTC date of the lead-time horizon the projection was evaluated at",
            example = "2026-01-20",
            requiredMode = REQUIRED)
    @NotNull
    private String leadHorizonDate;

    @Schema(
            description = "Where the resolved lead time came from",
            example = "POLICY_OVERRIDE",
            allowableValues = {"POLICY_OVERRIDE", "FEED", "DEFAULT"},
            requiredMode = REQUIRED)
    @NotNull
    private String leadTimeSource;

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
            description = "Whether a batch scan run now would consider this policy triggered"
                    + " (projected available below minimum)",
            example = "true",
            requiredMode = REQUIRED)
    private boolean wouldTrigger;

    @Schema(
            description = "Quantity a scan would replenish now: replenish-to-max after in-progress netting,"
                    + " rounded up to the policy's order multiple; zero when in-progress supply already covers"
                    + " the need or the policy is not triggered",
            example = "12",
            requiredMode = REQUIRED)
    private int suggestedQuantity;

    @Schema(
            description = "Earliest date at which the projected available quantity goes below zero;"
                    + " absent when the policy is not triggered or no stock-out is projected within"
                    + " the lead-time horizon",
            example = "2026-01-20",
            requiredMode = NOT_REQUIRED)
    private String deadlineDate;

    @Schema(
            description = "Preferred sourcing channel configured on the policy",
            example = "EITHER",
            requiredMode = REQUIRED)
    @NotNull
    private String preferredSourceType;
}
