package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "Batch response containing one restriction-evaluation result per requested item")
public record RestrictionEvaluationResponse(
        @Schema(description = "Per-item restriction evaluation results", example = "[]", requiredMode = REQUIRED)
                @NotNull List<RestrictionEvaluationResult> results) {}
