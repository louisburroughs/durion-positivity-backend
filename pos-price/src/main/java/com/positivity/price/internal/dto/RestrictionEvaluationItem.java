package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.price.internal.enums.EvaluationContext;
import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.ServiceTag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "Single product/context entry to evaluate against sale-restriction rules")
public record RestrictionEvaluationItem(
        @Schema(
                description = "Product to evaluate restrictions for",
                example = "7f3c35db-b908-42fa-83f1-2ef46a3c2149",
                requiredMode = REQUIRED)
        @NotNull
        UUID productId,

        @Schema(
                description = "Location scope tag for the evaluation",
                example = "RETAIL_STORE",
                requiredMode = REQUIRED)
        @NotNull
        LocationTag locationTag,

        @Schema(
                description = "Service channel scope tag for the evaluation",
                example = "POS_SALE",
                requiredMode = REQUIRED)
        @NotNull
        ServiceTag serviceTag,

        @Schema(
                description = "Transaction lifecycle context for the evaluation",
                example = "CHECKOUT",
                requiredMode = REQUIRED)
        @NotNull
        EvaluationContext context) {}
