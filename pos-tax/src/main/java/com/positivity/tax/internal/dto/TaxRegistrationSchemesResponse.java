package com.positivity.tax.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Response of the registration-schemes read ({@code GET /v1/tax/registration-schemes}, CAP:550 #2623).
 * <p>
 * The closed vocabulary of tax-registration schemes, per country: the code, its label, whether its holder is
 * a business or a person, and whether a registration under it belongs to one region (and to which). Every
 * value is configuration held for expert advice, never tax law, so {@code source} is always {@code STUB}.
 * A well-formed country with no configured scheme answers an empty list.
 *
 * @param source  always {@code STUB}
 * @param schemes the configured schemes, by country code then scheme code
 */
@Schema(
        name = "TaxRegistrationSchemesResponse",
        description = "The tax-registration schemes configured per country, a closed vocabulary")
public record TaxRegistrationSchemesResponse(
        @Schema(
                description = "Origin of the answer; always STUB, because every value is a placeholder",
                example = "STUB",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String source,

        @Schema(
                description = "The schemes, ordered by countryCode then scheme; empty for a country without any",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<SchemeEntry> schemes) {
    /**
     * One scheme.
     *
     * @param scheme      the code, unique across countries
     * @param countryCode the country the scheme belongs to
     * @param label       its label
     * @param holderType  {@code BUSINESS} or {@code PERSON}
     * @param regional    whether a registration under it belongs to one region
     * @param regions     the allowed subdivision codes without the country prefix; empty unless regional
     */
    @Schema(name = "TaxRegistrationScheme", description = "One configured tax-registration scheme")
    public record SchemeEntry(
            @Schema(description = "Scheme code, unique across countries", example = "QST")
            String scheme,

            @Schema(description = "ISO 3166-1 alpha-2 country code", example = "CA")
            String countryCode,

            @Schema(description = "Label shown beside the code", example = "Quebec sales tax (placeholder)")
            String label,

            @Schema(description = "Who holds a registration under the scheme", example = "BUSINESS")
            String holderType,

            @Schema(description = "Whether a registration belongs to one region", example = "true")
            boolean regional,

            @Schema(
                    description = "Allowed ISO 3166-2 subdivision codes without the country prefix;"
                            + " empty unless regional",
                    example = "[\"QC\"]")
            List<String> regions) {}
}
