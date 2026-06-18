package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.ServiceTag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Request payload to create a product sale-restriction rule")
public record CreateRestrictionRuleRequest(
        @Schema(
                        description = "Product the restriction rule applies to",
                        example = "7f3c35db-b908-42fa-83f1-2ef46a3c2149",
                        requiredMode = REQUIRED)
                @NotNull UUID productId,
        @Schema(description = "Location scope tag for the restriction", example = "RETAIL_STORE", requiredMode = REQUIRED)
                @NotNull LocationTag locationTag,
        @Schema(description = "Service channel scope tag for the restriction", example = "POS_SALE", requiredMode = REQUIRED)
                @NotNull ServiceTag serviceTag,
        @Schema(description = "Date the restriction rule becomes effective", example = "2026-03-01", requiredMode = REQUIRED)
                @NotNull LocalDate effectiveFrom,
        @Schema(
                        description = "Optional date the restriction rule stops being effective",
                        example = "2026-12-31",
                        requiredMode = NOT_REQUIRED)
                LocalDate effectiveTo,
        @Schema(description = "Optional pricing policy version associated with the rule", example = "3", requiredMode = NOT_REQUIRED)
                Integer policyVersion,
        @Schema(
                        description = "Whether the restriction can be overridden with authorization",
                        example = "true",
                        requiredMode = REQUIRED)
                boolean overrideable) {}
