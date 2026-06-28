package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

@Schema(description = "Batch request to evaluate sale-restriction rules for one or more product contexts")
public record RestrictionEvaluationRequest(
        @Schema(
                description = "Product/context entries to evaluate; must contain at least one item",
                requiredMode = REQUIRED)
        @NotEmpty
        @Valid
        List<RestrictionEvaluationItem> items) {}
