package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.price.internal.enums.EvaluationContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "Request payload to override a sale-restriction rule for a transaction")
public record RestrictionOverrideRequest(
        @Schema(
                        description = "Restriction rule being overridden",
                        example = "f51d2c5b-a1f2-4f4e-a7cf-4e7b1752e6ab",
                        requiredMode = REQUIRED)
                @NotNull UUID ruleId,
        @Schema(
                        description = "Transaction the override applies to",
                        example = "1f0c2b9a-7e44-4f0e-9c1b-2a6d8e3f4b50",
                        requiredMode = REQUIRED)
                @NotNull UUID transactionId,
        @Schema(
                        description = "Product the override applies to",
                        example = "7f3c35db-b908-42fa-83f1-2ef46a3c2149",
                        requiredMode = REQUIRED)
                @NotNull UUID productId,
        @Schema(description = "Transaction lifecycle context for the override", example = "CHECKOUT", requiredMode = REQUIRED)
                @NotNull EvaluationContext overrideContext,
        @Schema(description = "Reason code justifying the override", example = "MANAGER_APPROVAL", requiredMode = REQUIRED)
                @NotBlank String reasonCode,
        @Schema(
                        description = "Optional free-text notes for the override",
                        example = "Customer is a licensed reseller",
                        requiredMode = NOT_REQUIRED)
                String notes,
        @Schema(
                        description = "Optional identifier of the approver who authorized the override",
                        example = "user-001",
                        requiredMode = NOT_REQUIRED)
                String approvedBy) {}
