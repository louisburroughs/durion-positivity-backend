package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request payload for upserting a pricing guardrail policy")
public class GuardrailPolicyUpsertRequestDto {

    @NotNull
    @Schema(
            description = "Scope identifier the guardrail policy applies to",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID scopeId;

    @NotNull
    @DecimalMin(value = "0.0")
    @DecimalMax(value = "100.0")
    @Schema(description = "Minimum allowed margin percent", example = "20.0", requiredMode = REQUIRED)
    private BigDecimal minMarginPercent;

    @NotNull
    @DecimalMin(value = "0.0")
    @DecimalMax(value = "100.0")
    @Schema(description = "Maximum allowed discount percent", example = "15.0", requiredMode = REQUIRED)
    private BigDecimal maxDiscountPercent;

    @NotNull
    @DecimalMin(value = "0.0")
    @DecimalMax(value = "100.0")
    @Schema(
            description = "Threshold percent below which overrides are auto-approved",
            example = "5.0",
            requiredMode = REQUIRED)
    private BigDecimal autoApprovalThresholdPercent;
}
