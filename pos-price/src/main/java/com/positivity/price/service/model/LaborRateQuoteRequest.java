package com.positivity.price.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A labor-rate resolution query (#1575 Tier 0, T0-3): whose rate, for what class of work, at
 * what moment, and which matrix steps the writer has agreed apply.
 *
 * <p>Scope fields follow the widening convention the rate table stores: a null narrows nothing
 * and resolves the platform default, and the response's {@code scope} says how specific the
 * answer actually was.
 *
 * @param locationId the location quoting the work; null resolves the platform default rate
 * @param operationCategory {@code REPAIR | DIAGNOSTIC | MAINTENANCE | TIRE_SERVICE}; null asks
 *     for the category-agnostic rate
 * @param adjustmentCodes matrix steps the writer opted into (corrosion, after-hours, a fleet
 *     contract); an unknown or out-of-window code is simply not applied, never an error
 * @param at the moment to price at; null means now. A re-quote of an old estimate passes the
 *     original instant so the number is reproducible.
 */
@Schema(
        name = "LaborRateQuoteRequest",
        description = "Whose labor rate, for what class of work, at what moment, and which labor-matrix"
                + " steps apply. Null scope fields widen to the platform default.")
public record LaborRateQuoteRequest(
        @Schema(description = "Location quoting the work; null = platform default rate.") @Nullable
        UUID locationId,

        @Schema(
                description = "Operation category; null = the category-agnostic rate.",
                example = "TIRE_SERVICE",
                allowableValues = {"REPAIR", "DIAGNOSTIC", "MAINTENANCE", "TIRE_SERVICE"})
        @Size(max = 32)
        @Nullable
        String operationCategory,

        @Schema(
                description = "Labor-matrix step codes the writer opted into; unknown codes are ignored.",
                example = "[\"CORROSION\"]")
        @Nullable
        List<String> adjustmentCodes,

        @Schema(description = "Moment to price at; null = now.") @Nullable
        Instant at) {

    /** The common case: a location and a category, no matrix, priced now. */
    public static LaborRateQuoteRequest of(@Nullable UUID locationId, @Nullable String operationCategory) {
        return new LaborRateQuoteRequest(locationId, operationCategory, List.of(), null);
    }

    /** Never null downstream, so callers do not each re-handle the omitted-list case. */
    @NonNull
    public List<String> adjustmentCodesOrEmpty() {
        return adjustmentCodes == null ? List.of() : adjustmentCodes;
    }
}
