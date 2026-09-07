package com.positivity.price.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Typed answer to a labor-rate resolution (#1575 Tier 0, T0-3). This is the degradation contract
 * of the scoped synchronous read, and it mirrors the pos-catalog labor-time edge on purpose: a
 * miss never surfaces as an exception, always as a non-{@link Status#RESOLVED} status the caller
 * renders around — the estimate line stays editable and the writer types the price.
 *
 * <p>{@code steps} itemises the matrix so an invoice can show why the rate is what it is; a
 * charge a customer cannot see the derivation of is a charge they will dispute.
 *
 * @param status whether a rate was found, and if not, why not
 * @param hourlyRate the rate after the matrix, in {@code currency}; present only when RESOLVED
 * @param baseHourlyRate the rate before the matrix, so the two are comparable on the quote
 * @param currency ISO currency of both amounts
 * @param scope how specific the answering rate row was
 * @param rateId the row that answered — the provenance a re-quote can pin to
 * @param effectiveFrom start of the answering row's window
 * @param steps the matrix steps applied, in order, each with the rate it produced
 */
@Schema(
        name = "LaborRateQuoteResponse",
        description = "The resolved hourly labor rate with its matrix derivation, or a typed miss."
                + " Callers must degrade on non-RESOLVED statuses — render the line without a price —"
                + " never fail their flow.")
public record LaborRateQuoteResponse(
        @Schema(description = "Whether a rate was found, and if not, why not.") @NonNull
        Status status,

        @Schema(description = "Rate after the matrix; only when RESOLVED.", example = "142.50") @Nullable
        BigDecimal hourlyRate,

        @Schema(description = "Rate before the matrix; only when RESOLVED.", example = "125.00") @Nullable
        BigDecimal baseHourlyRate,

        @Schema(description = "ISO currency of both amounts.", example = "USD") @Nullable
        String currency,

        @Schema(description = "How specific the answering rate row was.", example = "LOCATION_CATEGORY") @Nullable
        Scope scope,

        @Schema(description = "Identifier of the rate row that answered.") @Nullable
        UUID rateId,

        @Schema(description = "Start of the answering row's effective window.") @Nullable
        Instant effectiveFrom,

        @Schema(description = "Matrix steps applied, in order.") @NonNull
        List<AppliedAdjustment> steps) {

    /** Typed resolution outcomes; misses are statuses, never errors. */
    @Schema(name = "LaborRateQuoteStatus")
    public enum Status {
        /** A rate was found; the scope and rate id say which row answered. */
        RESOLVED,
        /** No rate row covers this scope at this moment — not even a platform default. */
        NO_RATE_AVAILABLE
    }

    /** How specific the answering row was, narrowest first. */
    @Schema(name = "LaborRateScope")
    public enum Scope {
        /** The location's own rate for this operation category. */
        LOCATION_CATEGORY,
        /** The location's rate for every category. */
        LOCATION_DEFAULT,
        /** The platform rate for this operation category. */
        PLATFORM_CATEGORY,
        /** The platform rate for every category — the last answer before a miss. */
        PLATFORM_DEFAULT
    }

    /**
     * One matrix step and what it produced.
     *
     * @param code the step's code, as the request named it
     * @param type {@code PERCENT} or {@code FIXED}
     * @param value the step's configured value
     * @param resultingRate the running rate after this step
     */
    @Schema(name = "AppliedLaborRateAdjustment")
    public record AppliedAdjustment(
            @Schema(description = "Step code.", example = "CORROSION") @NonNull
            String code,

            @Schema(description = "PERCENT or FIXED.", example = "PERCENT") @NonNull
            String type,

            @Schema(description = "Configured value.", example = "15.0") @NonNull
            BigDecimal value,

            @Schema(description = "Running rate after this step.", example = "143.75") @NonNull
            BigDecimal resultingRate) {}

    public static LaborRateQuoteResponse miss() {
        return new LaborRateQuoteResponse(Status.NO_RATE_AVAILABLE, null, null, null, null, null, null, List.of());
    }
}
