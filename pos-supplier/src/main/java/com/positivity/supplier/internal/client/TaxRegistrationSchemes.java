package com.positivity.supplier.internal.client;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * pos-tax's registration-schemes answer (CAP:550 #2623), relayed unchanged by the vendor form's front door and read
 * by the vendor entry check. {@code holderType} is classification data for later readers: nothing in pos-supplier
 * outside this client branches on it (Security ruling on #2617, ruling 1).
 *
 * @param source  always {@code STUB} today
 * @param schemes the configured schemes, by country then code
 */
@Schema(
        name = "TaxRegistrationSchemes",
        description = "The tax-registration schemes a vendor may be filed under, per country, as pos-tax answers them")
public record TaxRegistrationSchemes(
        @Schema(description = "Origin of the list; STUB while the vocabulary is a placeholder", example = "STUB")
        String source,

        @Schema(description = "The schemes, ordered by countryCode then scheme")
        List<Scheme> schemes) {

    /**
     * One scheme.
     *
     * @param scheme      the code, unique across countries
     * @param countryCode ISO 3166-1 alpha-2
     * @param label       the label shown beside the code
     * @param holderType  {@code BUSINESS} or {@code PERSON}
     * @param regional    whether a registration under it belongs to one region
     * @param regions     the allowed subdivision codes without the country prefix; empty unless regional
     */
    @Schema(name = "TaxRegistrationScheme", description = "One tax-registration scheme")
    public record Scheme(
            @Schema(example = "QST") String scheme,
            @Schema(example = "CA") String countryCode,

            @Schema(example = "Quebec sales tax registration (placeholder)")
            String label,

            @Schema(example = "BUSINESS") String holderType,
            @Schema(example = "true") boolean regional,
            @Schema(example = "[\"QC\"]") List<String> regions) {}

    /** The schemes by code (codes are unique across countries). */
    public @NonNull Map<String, Scheme> byCode() {
        Map<String, Scheme> byCode = new LinkedHashMap<>();
        for (Scheme scheme : schemes == null ? List.<Scheme>of() : schemes) {
            byCode.put(scheme.scheme(), scheme);
        }
        return byCode;
    }
}
