package com.positivity.tax.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response of the stated-tax plausibility check ({@code POST /v1/tax/plausibility-checks}, CAP:550 S32b,
 * AW55). It never carries the supplier's registration number, only whether it is well formed.
 *
 * @param outcome                             {@code RATE_UNAVAILABLE} when a stated amount above zero is
 *                                            unrated (its regime covers the region but has no row on
 *                                            {@code asOf}), otherwise {@code PLAUSIBLE}
 * @param ratesUsed                           the rate row behind each rated stated regime
 * @param maximums                            each rated or not-levied stated regime's plausible maximum; an
 *                                            unrated regime has none
 * @param supplierRegistrationRequired        whether the receipt total reaches an evidence rule requiring the
 *                                            supplier's number on a drawer receipt
 * @param supplierRegistrationNumberWellFormed whether the number matches the country's supplier regime shape;
 *                                            {@code null} when no number was sent or the country names no
 *                                            supplier regime
 * @param asOf                                the date the check used
 * @param source                              always {@code STUB}
 */
@Schema(name = "TaxPlausibilityCheckResponse", description = "Whether a receipt's stated tax is plausible")
public record PlausibilityCheckResponse(
        @Schema(
                description = "RATE_UNAVAILABLE when a stated amount above zero is unrated (its regime covers the"
                        + " region but has no rate row on asOf), otherwise PLAUSIBLE; a caller never reads"
                        + " RATE_UNAVAILABLE as plausible for recovery",
                example = "PLAUSIBLE",
                allowableValues = {"PLAUSIBLE", "RATE_UNAVAILABLE"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String outcome,

        @Schema(
                description = "Rate row behind each rated stated regime (a row of the regime is in effect in the"
                        + " region on asOf); an unrated or not-levied regime has none",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<RateUsed> ratesUsed,

        @Schema(
                description = "Plausible maximum of each rated or not-levied stated regime (a not-levied regime has"
                        + " r = 0, so its maximum is the tolerance); an unrated regime has none",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<RegimeMaximum> maximums,

        @Schema(
                description = "Whether the receipt total reaches an evidence rule that requires the supplier's"
                        + " registration number on a drawer receipt on asOf; it never changes outcome",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean supplierRegistrationRequired,

        @Schema(
                description = "Whether the supplier's number matches the shape of the country's supplier regime;"
                        + " null when no number was sent or the country names no supplier regime. It never"
                        + " changes outcome",
                example = "true",
                nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Nullable
        Boolean supplierRegistrationNumberWellFormed,

        @Schema(
                description = "Date the check used",
                example = "2026-10-08",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate asOf,

        @Schema(
                description = "Origin of the answer; always STUB, because every value is a placeholder",
                example = "STUB",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String source) {

    /**
     * The rate row behind one stated regime.
     *
     * @param regime  the regime
     * @param taxType the tax type of the row
     * @param rate    the rate, as a decimal fraction
     */
    @Schema(name = "TaxPlausibilityRateUsed", description = "The rate row one stated regime was bounded by")
    public record RateUsed(
            @Schema(description = "Regime code", example = "REGIME_1", requiredMode = Schema.RequiredMode.REQUIRED)
            String regime,

            @Schema(
                    description = "Tax-type code of the rate row",
                    example = "ZZ_LEVY",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String taxType,

            @Schema(
                    description = "Rate as a decimal fraction",
                    example = "0.07",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            BigDecimal rate) {}

    /**
     * One stated regime's plausible maximum.
     *
     * @param regime  the regime
     * @param maximum the largest plausible amount, in the request currency
     */
    @Schema(name = "TaxPlausibilityMaximum", description = "The largest plausible amount for one stated regime")
    public record RegimeMaximum(
            @Schema(description = "Regime code", example = "REGIME_1", requiredMode = Schema.RequiredMode.REQUIRED)
            String regime,

            @Schema(
                    description = "Largest plausible amount: the total times r over one plus r, rounded up to the"
                            + " minor unit, plus the configured tolerance",
                    example = "9.87",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            BigDecimal maximum) {}
}
