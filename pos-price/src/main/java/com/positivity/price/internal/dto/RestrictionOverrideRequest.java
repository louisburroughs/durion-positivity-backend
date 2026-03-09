package com.positivity.price.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

import com.positivity.price.internal.enums.EvaluationContext;

public record RestrictionOverrideRequest(
        @NotNull UUID ruleId,
        @NotNull UUID transactionId,
        @NotNull UUID productId,
        @NotNull EvaluationContext overrideContext,
        @NotBlank String reasonCode,
        String notes,
        String approvedBy) {
}