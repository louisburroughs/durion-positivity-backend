package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The tax regimes configured for one country, each with the regions it covers and the tax types registered and
 * recovered under it (CAP:550 #2659), relayed from pos-tax's tax-types stub ({@code GET /v1/tax/tax-types}) through
 * accounting's front door (ADR-0071, AW59). It feeds the regime choices of the tax-registration panel, so no client
 * hard-codes a regime. Every value is pos-tax configuration held for expert advice (AW48, OI-4); no code names one.
 *
 * @param countryCode the country, ISO 3166-1 alpha-2: the one asked for, else the tax country
 * @param source      {@code STUB} while the values are placeholders
 * @param regimes     the configured regimes in configured order; empty when the country configures none
 */
@Schema(
        name = "TaxRegimesResponse",
        description = "The tax regimes configured for one country, with their regions and tax types")
public record TaxRegimesResponse(
        @Schema(description = "The country, ISO 3166-1 alpha-2", example = "ZZ", requiredMode = REQUIRED)
        String countryCode,

        @Schema(
                description = "Where the lists come from; STUB while they are placeholders held for expert advice",
                example = "STUB",
                requiredMode = REQUIRED)
        String source,

        @ArraySchema(
                arraySchema =
                        @Schema(
                                description = "The configured regimes in configured order; empty when the country"
                                        + " configures none, so no registration can be recorded there",
                                requiredMode = REQUIRED))
        List<Regime> regimes) {

    public TaxRegimesResponse {
        regimes = regimes == null ? List.of() : List.copyOf(regimes);
    }

    /**
     * One configured regime.
     *
     * @param regime   the regime code, as a registration names it
     * @param regions  the region codes it covers; empty means the whole country
     * @param taxTypes the tax types registered and recovered under it, in configured order
     */
    @Schema(name = "TaxRegime", description = "One configured tax regime, its regions and its tax types")
    public record Regime(
            @Schema(
                    description = "The regime code, as a tax registration names it",
                    example = "ZZ_REGIME_1",
                    requiredMode = REQUIRED)
            String regime,

            @ArraySchema(
                    arraySchema =
                            @Schema(
                                    description = "The region codes the regime covers; empty means the whole country",
                                    requiredMode = REQUIRED),
                    schema = @Schema(example = "R1"))
            List<String> regions,

            @ArraySchema(
                    arraySchema =
                            @Schema(
                                    description = "The tax types registered and recovered under the regime",
                                    requiredMode = REQUIRED))
            List<TaxType> taxTypes) {

        public Regime {
            regions = regions == null ? List.of() : List.copyOf(regions);
            taxTypes = taxTypes == null ? List.of() : List.copyOf(taxTypes);
        }
    }

    /**
     * One tax type of a regime.
     *
     * @param taxType          the configured tax-type code
     * @param jurisdictionType the jurisdiction level it is levied at
     */
    @Schema(name = "TaxRegimeTaxType", description = "One tax type registered and recovered under a regime")
    public record TaxType(
            @Schema(description = "The configured tax-type code", example = "ZZ_LEVY", requiredMode = REQUIRED)
            String taxType,

            @Schema(
                    description = "The jurisdiction level the tax type is levied at",
                    example = "COUNTRY",
                    allowableValues = {"COUNTRY", "STATE", "PROVINCE", "COUNTY", "CITY", "DISTRICT", "SPECIAL"},
                    requiredMode = REQUIRED)
            String jurisdictionType) {}
}
