package com.positivity.invoice.internal.dto;

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
public record FinalizationEligibilityResult(
        boolean eligible, @Nullable String reason, boolean requiresManagerApproval) {}
