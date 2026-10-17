package com.positivity.tax.internal.service;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.config.TaxProperties.RegistrationScheme;
import com.positivity.tax.internal.dto.TaxRegistrationSchemesResponse;
import com.positivity.tax.internal.dto.TaxRegistrationSchemesResponse.SchemeEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The registration-schemes stub (CAP:550 #2623, AW48): the closed vocabulary of tax-registration schemes, per
 * country, with each scheme's holder type and regionality. Built once from {@code pos.tax.registration-schemes};
 * the constructor is the startup check, so a bad entry stops the service with an {@link IllegalStateException}
 * naming the offending property.
 * <p>
 * Nothing here names a country or a scheme: every value is configuration held for expert advice (OI-4), and the
 * shipped rows are placeholders. Country codes are map keys and must be upper case (relaxed binding lower-cases an
 * environment-variable key, and this check then refuses it); schemes are a list with explicit codes.
 * <p>
 * The startup check refuses: a country key that is not an upper-case ISO 3166-1 alpha-2 code (assigned or
 * user-assigned, so fixtures may use {@code ZZ}); a code outside {@code ^[A-Z][A-Z_]{1,15}$} (no digit: a code is
 * stored and published beside a registration's {@code last4}, ADR-0072 Decision 2) or repeated in <em>any</em>
 * country (a code alone identifies a scheme); a blank label or one over 100 characters; an unknown holder type;
 * a regional scheme without regions, a non-regional scheme with regions, or a region outside {@code ^[A-Z]{1,3}$}
 * or repeated.
 */
@Component
public class TaxRegistrationSchemes {

    /** The answer's {@code source}: every value is a placeholder. */
    public static final String SOURCE = "STUB";

    /** The holder types a scheme may name. */
    public static final Set<String> HOLDER_TYPES = Set.of("BUSINESS", "PERSON");

    /** The shape of a scheme code: a subset of the vendor entry's {@code scheme} shape, with no digit. */
    public static final Pattern SCHEME_CODE = Pattern.compile("^[A-Z][A-Z_]{1,15}$");

    /** The shape of a region: the subdivision part of an ISO 3166-2 code, letters only. */
    public static final Pattern REGION_CODE = Pattern.compile("^[A-Z]{1,3}$");

    private static final String PREFIX = "pos.tax.registration-schemes.";
    private static final int MAX_LABEL = 100;

    /** ISO 3166-1 alpha-2: the assigned codes plus the user-assigned elements ISO reserves for private use. */
    private static final Set<String> ALPHA_2_CODES = alpha2Codes();

    private final List<SchemeEntry> schemes;

    public TaxRegistrationSchemes(TaxProperties properties) {
        List<SchemeEntry> built = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        properties.getRegistrationSchemes().forEach((country, configured) -> {
            String prefix = PREFIX + country;
            if (!ALPHA_2_CODES.contains(country)) {
                throw invalid(prefix, "the country code is not an upper-case ISO 3166-1 alpha-2 code");
            }
            List<RegistrationScheme> source = configured == null ? List.of() : configured;
            for (int i = 0; i < source.size(); i++) {
                SchemeEntry entry = validate(prefix + "[" + i + "]", country, source.get(i));
                if (!codes.add(entry.scheme())) {
                    throw invalid(
                            prefix + "[" + i + "].scheme",
                            "repeats scheme " + entry.scheme() + "; a code is unique across every country");
                }
                built.add(entry);
            }
        });
        built.sort(Comparator.comparing(SchemeEntry::countryCode).thenComparing(SchemeEntry::scheme));
        this.schemes = List.copyOf(built);
    }

    /**
     * The schemes read.
     *
     * @param countryCode an upper-case ISO 3166-1 alpha-2 code, or {@code null} for every configured country
     * @return the schemes, by country then code, empty for a country without any, with {@code source = STUB}
     */
    @NonNull
    public TaxRegistrationSchemesResponse read(@Nullable String countryCode) {
        if (countryCode == null) {
            return new TaxRegistrationSchemesResponse(SOURCE, schemes);
        }
        return new TaxRegistrationSchemesResponse(
                SOURCE,
                schemes.stream()
                        .filter(s -> s.countryCode().equals(countryCode))
                        .toList());
    }

    // ---------------------------------------------------------------------------------------
    // Startup check
    // ---------------------------------------------------------------------------------------

    private static SchemeEntry validate(String prefix, String country, @Nullable RegistrationScheme scheme) {
        if (scheme == null) {
            throw invalid(prefix, "the scheme is empty");
        }
        String code = scheme.getScheme();
        if (code == null || !SCHEME_CODE.matcher(code).matches()) {
            throw invalid(prefix + ".scheme", "must match " + SCHEME_CODE.pattern());
        }
        String label = scheme.getLabel();
        if (label == null || label.isBlank() || label.length() > MAX_LABEL) {
            throw invalid(prefix + ".label", "must be 1-" + MAX_LABEL + " characters");
        }
        String holderType = scheme.getHolderType();
        if (holderType == null || !HOLDER_TYPES.contains(holderType)) {
            throw invalid(prefix + ".holder-type", "must be one of " + HOLDER_TYPES);
        }
        Boolean regional = scheme.getRegional();
        if (regional == null) {
            throw invalid(prefix + ".regional", "must be true or false");
        }
        List<String> regions = scheme.getRegions() == null ? List.of() : scheme.getRegions();
        if (regional && regions.isEmpty()) {
            throw invalid(prefix + ".regions", "a regional scheme needs at least one region");
        }
        if (!regional && !regions.isEmpty()) {
            throw invalid(prefix + ".regions", "a scheme that is not regional has no regions");
        }
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < regions.size(); k++) {
            String region = regions.get(k);
            String property = prefix + ".regions[" + k + "]";
            if (region == null || !REGION_CODE.matcher(region).matches()) {
                throw invalid(property, "must match " + REGION_CODE.pattern());
            }
            if (!seen.add(region)) {
                throw invalid(property, "repeats region " + region + " in the scheme");
            }
        }
        return new SchemeEntry(code, country, label, holderType, regional, List.copyOf(regions));
    }

    private static Set<String> alpha2Codes() {
        Set<String> codes = new HashSet<>(Arrays.asList(Locale.getISOCountries()));
        codes.add("AA");
        codes.add("ZZ");
        for (char c = 'M'; c <= 'Z'; c++) {
            codes.add("Q" + c);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            codes.add("X" + c);
        }
        return Set.copyOf(codes);
    }

    private static IllegalStateException invalid(String property, String reason) {
        return new IllegalStateException("Invalid tax configuration " + property + ": " + reason);
    }
}
