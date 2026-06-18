package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.PriceBookRuleConditionType;
import com.positivity.catalog.internal.entity.PriceBookRuleStatus;
import com.positivity.catalog.internal.entity.PriceBookRuleTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Price book rule detail")
public class PriceBookRuleDto {

    @Schema(
            description = "Rule identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID ruleId;

    @Schema(
            description = "Identifier of the owning price book",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID priceBookId;

    @Schema(
            description = "Target type the rule applies to",
            example = "SKU",
            implementation = PriceBookRuleTargetType.class,
            requiredMode = REQUIRED)
    private PriceBookRuleTargetType targetType;

    @Schema(
            description = "Identifier of the targeted entity (SKU or category)",
            example = "3d129775-efeb-57f0-b949-7c0ecf9f0c2e",
            requiredMode = NOT_REQUIRED)
    private UUID targetId;

    @Schema(description = "Pricing logic expression applied by the rule", example = "MSRP * 0.9", requiredMode = REQUIRED)
    private String pricingLogic;

    @Schema(
            description = "Condition type gating the rule",
            example = "CUSTOMER_TIER",
            implementation = PriceBookRuleConditionType.class,
            requiredMode = NOT_REQUIRED)
    private PriceBookRuleConditionType conditionType;

    @Schema(description = "Value evaluated against the condition type", example = "GOLD", requiredMode = NOT_REQUIRED)
    private String conditionValue;

    @Schema(description = "Evaluation priority; lower wins", example = "10", requiredMode = NOT_REQUIRED)
    private Integer priority;

    @Schema(description = "Instant the rule becomes effective", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private OffsetDateTime effectiveStartAt;

    @Schema(description = "Instant the rule stops being effective", example = "2026-12-31T23:59:59Z", requiredMode = NOT_REQUIRED)
    private OffsetDateTime effectiveEndAt;

    @Schema(
            description = "Current status of the rule",
            example = "ACTIVE",
            implementation = PriceBookRuleStatus.class,
            requiredMode = REQUIRED)
    private PriceBookRuleStatus status;

    @Schema(
            description = "Identifier of the user that created the rule",
            example = "8b8df63e-18d8-4bde-a8f4-88bc36bc57d7",
            requiredMode = REQUIRED)
    private UUID createdByUserId;

    @Schema(description = "Timestamp the rule was created", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private OffsetDateTime createdAt;

    @Schema(description = "Timestamp the rule was last updated", example = "2026-01-16T11:00:00Z", requiredMode = NOT_REQUIRED)
    private OffsetDateTime updatedAt;

    @Schema(description = "Version for optimistic locking", example = "1", requiredMode = REQUIRED)
    private Long version;
}
