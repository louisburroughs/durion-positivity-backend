package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.price.internal.enums.RestrictionDecision;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

@Schema(description = "Restriction-evaluation outcome for a single product context")
public record RestrictionEvaluationResult(
        @Schema(
                        description = "Product the evaluation applies to",
                        example = "7f3c35db-b908-42fa-83f1-2ef46a3c2149",
                        requiredMode = REQUIRED)
                @NotNull UUID productId,
        @Schema(description = "Restriction decision for the product context", example = "ALLOW", requiredMode = REQUIRED)
                @NotNull RestrictionDecision decision,
        @Schema(
                        description = "Identifiers of rules that contributed to the decision",
                        example = "[]",
                        requiredMode = REQUIRED)
                @NotNull List<UUID> ruleIds,
        @Schema(
                        description = "Reason codes explaining the decision",
                        example = "[]",
                        requiredMode = REQUIRED)
                @NotNull List<String> reasonCodes) {}
