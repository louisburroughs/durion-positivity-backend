package com.positivity.tax.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response for the jurisdiction rate lookup endpoint (issue #1522, {@code GET /v1/tax/rates}).
 * <p>
 * {@code components} lists the per-jurisdiction rates that apply to the supplied address as of
 * {@code asOf}; {@code combinedRate} is their sum, both as decimal fractions
 * (see {@link TaxRateComponent}). This is a rate-only lookup — it does not calculate tax for
 * any line items — and {@code source} names the provider that resolved it (e.g.
 * {@code TEST_MODE}).
 *
 * @param countryCode  the queried country (echoed from the request)
 * @param regionCode   the queried region/subdivision, when supplied
 * @param city         the queried city, when supplied
 * @param postalCode   the queried postal/ZIP code
 * @param asOf         the effective date the rates were resolved for
 * @param components   the per-jurisdiction rates, in stable order
 * @param combinedRate the sum of {@code components[].rate}, as a decimal fraction
 * @param source       the name of the provider that resolved these rates
 */
@Schema(name = "TaxRateLookupResponse", description = "Jurisdiction tax rates resolved for a destination address")
public record TaxRateLookupResponse(
        @Schema(
                description = "Country code in ISO 3166-1 alpha-2 format",
                example = "US",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String countryCode,

        @Schema(
                description = "Region/subdivision code, when supplied",
                example = "CA",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String regionCode,

        @Schema(
                description = "Locality/city name, when supplied",
                example = "San Francisco",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String city,

        @Schema(description = "Postal/ZIP code", example = "94103", requiredMode = Schema.RequiredMode.REQUIRED)
        String postalCode,

        @Schema(
                description = "Effective date the rates were resolved for",
                example = "2026-08-27",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate asOf,

        @Schema(
                description = "Per-jurisdiction rates, as decimal fractions, in stable order",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<TaxRateComponent> components,

        @Schema(
                description = "Sum of components[].rate, as a decimal fraction",
                example = "0.0850",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal combinedRate,

        @Schema(
                description = "Name of the provider that resolved these rates",
                example = "TEST_MODE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String source) {}
