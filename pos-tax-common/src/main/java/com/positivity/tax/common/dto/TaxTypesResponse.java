package com.positivity.tax.common.dto;

import com.positivity.tax.common.enums.TaxJurisdictionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response of the tax-types read ({@code GET /v1/tax/tax-types}, CAP:550 S32a).
 * <p>
 * Projects one country's configured tax-type profile: the tax types it declares, the regime each
 * is registered and recovered under, the jurisdiction level it is levied at and its placeholder
 * recoverability. Callers read it instead of naming any tax type or regime in their own code
 * (ADR-0044 R2). Every value is configuration held for expert advice, never tax law, so
 * {@code source} is always {@code STUB}. A country with no profile answers empty lists and a
 * {@code null} currency.
 *
 * @param countryCode the queried country, echoed
 * @param currency    the country's configured ISO 4217 currency; {@code null} with no profile
 * @param taxTypes    the declared tax types, in configured order
 * @param regimes     the declared regimes, in configured order
 * @param source      always {@code STUB}
 */
@Schema(name = "TaxTypesResponse", description = "The tax types, regimes and currency configured for one country")
public record TaxTypesResponse(
        @Schema(
                description = "Country code in ISO 3166-1 alpha-2 format, echoed from the request",
                example = "US",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String countryCode,

        @Schema(
                description = "ISO 4217 currency configured for the country; null when the country has no profile",
                example = "USD",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        String currency,

        @Schema(
                description = "Tax types the country declares, in configured order; empty with no profile",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<TaxTypeEntry> taxTypes,

        @Schema(
                description = "Registration and recovery regimes the country declares; empty with no profile",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<RegimeEntry> regimes,

        @Schema(
                description = "Origin of the answer; always STUB, because every value is a placeholder",
                example = "STUB",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String source) {

    /**
     * One declared tax type.
     *
     * @param taxType             the configured tax-type code
     * @param regime              the regime it is registered and recovered under; {@code null} for none
     * @param jurisdictionType    the jurisdiction level it is levied at
     * @param inputTaxRecoverable the placeholder recoverability held for expert advice
     */
    @Schema(name = "TaxTypeEntry", description = "One tax type a country declares, with its regime and level")
    public record TaxTypeEntry(
            @Schema(
                    description = "Tax-type code as the country profile declares it (1-32 upper-case letters, digits"
                            + " or underscores); the vocabulary is configuration only",
                    example = "ZZ_LEVY",
                    pattern = "^[A-Z0-9_]{1,32}$",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String taxType,

            @Schema(
                    description = "Regime the tax type is registered and recovered under; null when it has none",
                    example = "REGIME_1",
                    nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable
            String regime,

            @Schema(
                    description = "Jurisdiction level the tax type is levied at",
                    example = "COUNTRY",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            TaxJurisdictionType jurisdictionType,

            @Schema(
                    description = "Placeholder recoverability of the tax type, held for expert advice",
                    example = "true",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean inputTaxRecoverable) {}

    /**
     * One declared registration and recovery regime.
     *
     * @param regime  the regime name
     * @param regions the region codes it covers; empty means the whole country
     */
    @Schema(name = "TaxRegimeEntry", description = "One registration and recovery regime a country declares")
    public record RegimeEntry(
            @Schema(description = "Regime name", example = "REGIME_1", requiredMode = Schema.RequiredMode.REQUIRED)
            String regime,

            @Schema(
                    description = "Region codes the regime covers; an empty list means the whole country",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> regions) {}
}
