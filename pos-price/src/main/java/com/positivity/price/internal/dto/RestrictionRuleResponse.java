package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.ServiceTag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Response payload representing a stored sale-restriction rule")
public record RestrictionRuleResponse(
        @Schema(
                description = "Restriction rule identifier",
                example = "f51d2c5b-a1f2-4f4e-a7cf-4e7b1752e6ab",
                requiredMode = REQUIRED)
        @NotNull
        UUID ruleId,

        @Schema(
                description = "Product the restriction rule applies to",
                example = "7f3c35db-b908-42fa-83f1-2ef46a3c2149",
                requiredMode = REQUIRED)
        @NotNull
        UUID productId,

        @Schema(
                description = "Location scope tag for the restriction",
                example = "RETAIL_STORE",
                requiredMode = REQUIRED)
        @NotNull
        LocationTag locationTag,

        @Schema(
                description = "Service channel scope tag for the restriction",
                example = "POS_SALE",
                requiredMode = REQUIRED)
        @NotNull
        ServiceTag serviceTag,

        @Schema(
                description = "Whether the restriction rule is currently active",
                example = "true",
                requiredMode = REQUIRED)
        boolean active,

        @Schema(
                description = "Date the restriction rule becomes effective",
                example = "2026-03-01",
                requiredMode = REQUIRED)
        @NotNull
        LocalDate effectiveFrom,

        @Schema(
                description = "Optional date the restriction rule stops being effective",
                example = "2026-12-31",
                requiredMode = NOT_REQUIRED)
        LocalDate effectiveTo) {}
