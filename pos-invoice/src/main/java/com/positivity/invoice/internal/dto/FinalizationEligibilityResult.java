package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Result of an invoice finalization eligibility check. Story #13 scaffold.
 *
 * @param eligible                Whether the invoice is eligible for
 *                                finalization.
 * @param reason                  Human-readable reason when not eligible
 *                                (nullable if eligible).
 * @param requiresManagerApproval Whether manager approval is required before
 *                                finalization proceeds.
 */
@Schema(description = "Result of an invoice finalization eligibility check")
public record FinalizationEligibilityResult(
        @Schema(description = "Whether the invoice is eligible for finalization", example = "true", requiredMode = REQUIRED)
                boolean eligible,
        @Schema(
                        description = "Human-readable reason when not eligible",
                        example = "Invoice total exceeds approval threshold",
                        requiredMode = NOT_REQUIRED)
                @Nullable
                String reason,
        @Schema(
                        description = "Whether manager approval is required before finalization proceeds",
                        example = "false",
                        requiredMode = REQUIRED)
                boolean requiresManagerApproval) {}
