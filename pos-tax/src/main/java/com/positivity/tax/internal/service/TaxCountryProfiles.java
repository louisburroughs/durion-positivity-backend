package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxTypesResponse.RegimeEntry;
import com.positivity.tax.common.dto.TaxTypesResponse.TaxTypeEntry;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.common.validation.TaxTypeCodes;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.config.TaxProperties.CountryProfile;
import com.positivity.tax.internal.config.TaxProperties.RateRow;
import com.positivity.tax.internal.config.TaxProperties.RegimeProfile;
import com.positivity.tax.internal.config.TaxProperties.TaxTypeProfile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The validated, immutable view of the per-country tax profiles and the per-country default
 * providers (CAP:550 S32a, ADR-0071 §3).
 * <p>
 * Built once from {@code pos.tax.countries} and {@code pos.tax.default-providers}. The constructor
 * is the startup check: any invalid entry throws {@link IllegalStateException} naming the
 * offending property, so the service refuses to start on a bad profile rather than price from it.
 * <p>
 * Nothing here names a country, regime or tax type: the shape is country-keyed and every value is
 * configuration held for expert advice (spec AW48, OI-4). The only plug-in id this story defines is
 * the self-hosted one, {@code <country>_SELF}, one per profiled country; a plug-in only ever serves
 * its own country, so a country's tax is never priced from another country's profile.
 */
@Component
public class TaxCountryProfiles {

    /** Suffix of the configuration-driven self-hosted plug-in id: {@code <country>_SELF}. */
    public static final String SELF_PLUGIN_SUFFIX = "_SELF";

    private static final String COUNTRIES = "pos.tax.countries.";
    private static final String DEFAULT_PROVIDERS = "pos.tax.default-providers.";
    private static final Pattern ALPHA_2 = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern REGION_CODE = Pattern.compile("^[A-Za-z0-9]{1,3}$");

    /**
     * ISO 3166-1 alpha-2: the officially assigned codes plus the user-assigned code elements
     * (AA, QM–QZ, XA–XZ, ZZ), which ISO reserves for private use such as test fixtures.
     */
    private static final Set<String> ALPHA_2_CODES = alpha2Codes();

    private final Map<String, CountryTaxProfile> profiles;
    private final Map<String, String> defaultProviders;

    public TaxCountryProfiles(TaxProperties properties) {
        Map<String, CountryTaxProfile> built = new LinkedHashMap<>();
        properties.getCountries().forEach((key, profile) -> {
            CountryTaxProfile validated = validateCountry(key, profile);
            built.put(validated.countryCode(), validated);
        });
        this.profiles = Map.copyOf(built);
        this.defaultProviders = validateDefaultProviders(properties.getDefaultProviders(), built.keySet());
    }

    /**
     * The profile of {@code countryCode}, when one is configured.
     *
     * @param countryCode an ISO 3166-1 alpha-2 code, any case; may be {@code null}
     * @return the profile, or empty
     */
    @NonNull
    public Optional<CountryTaxProfile> profile(@Nullable String countryCode) {
        return countryCode == null ? Optional.empty() : Optional.ofNullable(profiles.get(upper(countryCode)));
    }

    /**
     * The plug-in id {@code pos.tax.default-providers} routes {@code countryCode} to, when any.
     *
     * @param countryCode an ISO 3166-1 alpha-2 code, any case; may be {@code null}
     * @return the plug-in id, or empty when the country keeps the deployment-wide switch
     */
    @NonNull
    public Optional<String> defaultProvider(@Nullable String countryCode) {
        return countryCode == null ? Optional.empty() : Optional.ofNullable(defaultProviders.get(upper(countryCode)));
    }

    /**
     * Every validated profile.
     *
     * @return the profiles, keyed by upper-case country code
     */
    @NonNull
    public Map<String, CountryTaxProfile> all() {
        return profiles;
    }

    /**
     * The self-hosted plug-in id of a country.
     *
     * @param countryCode the upper-case country code
     * @return {@code <country>_SELF}
     */
    @NonNull
    public static String selfPluginId(@NonNull String countryCode) {
        return countryCode + SELF_PLUGIN_SUFFIX;
    }

    // ---------------------------------------------------------------------------------------
    // Startup check
    // ---------------------------------------------------------------------------------------

    @NonNull
    private static CountryTaxProfile validateCountry(@NonNull String key, @Nullable CountryProfile profile) {
        String prefix = COUNTRIES + key;
        if (!isAlpha2(key)) {
            throw invalid(prefix, "the country code is not ISO 3166-1 alpha-2");
        }
        CountryProfile source = profile == null ? new CountryProfile() : profile;
        int exponent = currencyExponent(prefix + ".currency", source.getCurrency());

        Map<String, TaxTypeEntry> taxTypes = validateTaxTypes(prefix, source);
        List<RegimeEntry> regimes = validateRegimes(prefix, source.getRegimes());
        Set<String> regimeNames = regimes.stream().map(RegimeEntry::regime).collect(Collectors.toSet());
        taxTypes.forEach((code, entry) -> {
            if (entry.regime() != null && !regimeNames.contains(entry.regime())) {
                throw invalid(
                        prefix + ".tax-types." + code + ".regime",
                        "regime " + entry.regime() + " is not declared under " + prefix + ".regimes");
            }
        });

        List<ConfiguredRate> rates = validateRates(prefix, source.getRates(), taxTypes);
        rejectOverlaps(prefix, rates, ConfiguredRate::taxType, "tax type");
        rejectOverlaps(prefix, rates, ConfiguredRate::regime, "regime");

        return new CountryTaxProfile(
                key, source.getCurrency().trim(), exponent, List.copyOf(taxTypes.values()), regimes, rates);
    }

    private static int currencyExponent(@NonNull String property, @Nullable String currency) {
        if (currency == null || currency.isBlank()) {
            throw invalid(property, "a currency is required");
        }
        try {
            int exponent = Currency.getInstance(currency.trim()).getDefaultFractionDigits();
            if (exponent < 0) {
                throw invalid(property, currency + " has no minor unit");
            }
            return exponent;
        } catch (IllegalArgumentException ex) {
            throw invalid(property, currency + " is not an ISO 4217 currency code");
        }
    }

    @NonNull
    private static Map<String, TaxTypeEntry> validateTaxTypes(@NonNull String prefix, @NonNull CountryProfile source) {
        Map<String, TaxTypeEntry> entries = new LinkedHashMap<>();
        source.getTaxTypes().forEach((code, profile) -> {
            String property = prefix + ".tax-types." + code;
            if (!TaxTypeCodes.isWellFormed(code)) {
                throw invalid(
                        property,
                        "'" + code + "' is not a tax-type code of 1 to 32 upper-case letters, digits or underscores");
            }
            String taxType = code;
            TaxTypeProfile typeProfile = profile == null ? new TaxTypeProfile() : profile;
            TaxJurisdictionType jurisdictionType = jurisdictionType(property, typeProfile.getJurisdictionType());
            if (typeProfile.getInputTaxRecoverable() == null) {
                throw invalid(property + ".input-tax-recoverable", "a value is required for every declared tax type");
            }
            entries.put(
                    code,
                    new TaxTypeEntry(
                            taxType,
                            blankToNull(typeProfile.getRegime()),
                            jurisdictionType,
                            typeProfile.getInputTaxRecoverable()));
        });
        return entries;
    }

    @NonNull
    private static TaxJurisdictionType jurisdictionType(@NonNull String property, @Nullable String value) {
        String jurisdictionProperty = property + ".jurisdiction-type";
        if (value == null || value.isBlank()) {
            throw invalid(jurisdictionProperty, "a jurisdiction type is required");
        }
        try {
            return Objects.requireNonNull(TaxJurisdictionType.fromValue(value));
        } catch (IllegalArgumentException ex) {
            throw invalid(jurisdictionProperty, value + " is not a TaxJurisdictionType code");
        }
    }

    @NonNull
    private static List<RegimeEntry> validateRegimes(
            @NonNull String prefix, @NonNull Map<String, RegimeProfile> regimes) {
        List<RegimeEntry> entries = new ArrayList<>();
        regimes.forEach((name, profile) -> {
            List<String> regions = profile == null ? List.of() : profile.getRegions();
            List<String> normalized = new ArrayList<>();
            for (int i = 0; i < regions.size(); i++) {
                normalized.add(regionCode(prefix + ".regimes." + name + ".regions[" + i + "]", regions.get(i)));
            }
            entries.add(new RegimeEntry(name, List.copyOf(normalized)));
        });
        return List.copyOf(entries);
    }

    @NonNull
    private static List<ConfiguredRate> validateRates(
            @NonNull String prefix, @NonNull List<RateRow> rows, @NonNull Map<String, TaxTypeEntry> taxTypes) {
        List<ConfiguredRate> rates = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String property = prefix + ".rates[" + i + "]";
            RateRow row = rows.get(i) == null ? new RateRow() : rows.get(i);
            String region = regionCode(property + ".region-code", row.getRegionCode());
            TaxTypeEntry type = row.getTaxType() == null
                    ? null
                    : taxTypes.get(row.getTaxType().trim());
            if (type == null) {
                throw invalid(
                        property + ".tax-type", row.getTaxType() + " is not declared under " + prefix + ".tax-types");
            }
            BigDecimal rate = row.getRate();
            if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
                throw invalid(property + ".rate", rate + " is outside [0, 1)");
            }
            if (row.getEffectiveFrom() == null) {
                throw invalid(property + ".effective-from", "an effective-from date is required");
            }
            if (row.getEffectiveTo() != null && row.getEffectiveTo().isBefore(row.getEffectiveFrom())) {
                throw invalid(property + ".effective-to", "effective-to is before effective-from");
            }
            rates.add(new ConfiguredRate(
                    i,
                    region,
                    type.taxType(),
                    type.regime(),
                    type,
                    rate,
                    row.getEffectiveFrom(),
                    row.getEffectiveTo()));
        }
        return List.copyOf(rates);
    }

    /**
     * Rejects two rows of one region that share a key (a tax type, or a regime) and are in effect
     * on the same date. The regime form is the generic "one rate per regime" rule: a stated regime
     * amount must be bounded by one rate.
     */
    private static void rejectOverlaps(
            @NonNull String prefix,
            @NonNull List<ConfiguredRate> rates,
            @NonNull Function<ConfiguredRate, @Nullable String> key,
            @NonNull String label) {
        for (int a = 0; a < rates.size(); a++) {
            ConfiguredRate first = rates.get(a);
            String firstKey = key.apply(first);
            if (firstKey == null) {
                continue;
            }
            for (int b = a + 1; b < rates.size(); b++) {
                ConfiguredRate second = rates.get(b);
                if (first.regionCode().equals(second.regionCode())
                        && firstKey.equals(key.apply(second))
                        && first.overlaps(second)) {
                    throw invalid(
                            prefix + ".rates[" + second.index() + "]",
                            "overlaps " + prefix + ".rates[" + first.index() + "]: two rows of region "
                                    + first.regionCode() + " and " + label + " " + firstKey
                                    + " are in effect on the same date");
                }
            }
        }
    }

    @NonNull
    private static Map<String, String> validateDefaultProviders(
            @NonNull Map<String, String> configured, @NonNull Set<String> profiled) {
        Map<String, String> routes = new LinkedHashMap<>();
        configured.forEach((country, plugin) -> {
            String property = DEFAULT_PROVIDERS + country;
            if (!isAlpha2(country)) {
                throw invalid(property, "the country code is not ISO 3166-1 alpha-2");
            }
            String pluginId = plugin == null ? "" : plugin.trim();
            if (!profiled.contains(country) || !selfPluginId(country).equals(pluginId)) {
                throw invalid(
                        property,
                        "names an unknown plug-in '" + pluginId + "'; the plug-ins for this country are "
                                + (profiled.contains(country)
                                        ? "[" + selfPluginId(country) + "]"
                                        : "[] (no profile under " + COUNTRIES + country + ")"));
            }
            routes.put(country, pluginId);
        });
        return Map.copyOf(routes);
    }

    @NonNull
    private static String regionCode(@NonNull String property, @Nullable String value) {
        if (value == null || !REGION_CODE.matcher(value.trim()).matches()) {
            throw invalid(property, "'" + value + "' is not a region code of 1 to 3 letters or digits");
        }
        return upper(value.trim());
    }

    private static boolean isAlpha2(@Nullable String code) {
        return code != null && ALPHA_2.matcher(code).matches() && ALPHA_2_CODES.contains(code);
    }

    @NonNull
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

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @NonNull
    private static String upper(@NonNull String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    @NonNull
    private static IllegalStateException invalid(@NonNull String property, @NonNull String reason) {
        return new IllegalStateException("Invalid tax configuration " + property + ": " + reason);
    }

    // ---------------------------------------------------------------------------------------
    // The validated model
    // ---------------------------------------------------------------------------------------

    /**
     * One configured rate row, validated.
     *
     * @param index         the row's index in configuration (for messages)
     * @param regionCode    the upper-case region code
     * @param taxType       the row's tax type
     * @param regime        the tax type's regime; {@code null} for none
     * @param definition    the tax type's declaration
     * @param rate          the rate as a decimal fraction
     * @param effectiveFrom inclusive first date
     * @param effectiveTo   inclusive last date; {@code null} for open-ended
     */
    public record ConfiguredRate(
            int index,
            @NonNull String regionCode,
            @NonNull String taxType,
            @Nullable String regime,
            @NonNull TaxTypeEntry definition,
            @NonNull BigDecimal rate,
            @NonNull LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {

        /**
         * Whether the row is in effect on {@code date} (both ends inclusive).
         *
         * @param date the date
         * @return {@code true} when in effect
         */
        public boolean inEffectOn(@NonNull LocalDate date) {
            return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
        }

        boolean overlaps(@NonNull ConfiguredRate other) {
            LocalDate thisEnd = effectiveTo == null ? LocalDate.MAX : effectiveTo;
            LocalDate otherEnd = other.effectiveTo() == null ? LocalDate.MAX : other.effectiveTo();
            return !effectiveFrom.isAfter(otherEnd) && !other.effectiveFrom().isAfter(thisEnd);
        }
    }

    /**
     * One country's validated profile.
     *
     * @param countryCode      the upper-case country code
     * @param currency         the ISO 4217 currency
     * @param currencyExponent the currency's minor-unit exponent: the rounding scale
     * @param taxTypes         the declared tax types, in configured order
     * @param regimes          the declared regimes, in configured order
     * @param rates            the rate rows
     */
    public record CountryTaxProfile(
            @NonNull String countryCode,
            @NonNull String currency,
            int currencyExponent,
            @NonNull List<TaxTypeEntry> taxTypes,
            @NonNull List<RegimeEntry> regimes,
            @NonNull List<ConfiguredRate> rates) {

        /**
         * The rows of {@code regionCode} in effect on {@code date}, one per tax type, in the
         * declared tax-type order.
         *
         * @param regionCode the region code, any case; may be {@code null}
         * @param date       the date
         * @return the rows; empty when none is configured
         */
        @NonNull
        public List<ConfiguredRate> ratesInEffect(@Nullable String regionCode, @NonNull LocalDate date) {
            if (regionCode == null || regionCode.isBlank()) {
                return List.of();
            }
            String region = upper(regionCode);
            List<ConfiguredRate> inEffect = new ArrayList<>();
            for (TaxTypeEntry type : taxTypes) {
                rates.stream()
                        .filter(rate -> rate.taxType().equals(type.taxType())
                                && rate.regionCode().equals(region)
                                && rate.inEffectOn(date))
                        .findFirst()
                        .ifPresent(inEffect::add);
            }
            return List.copyOf(inEffect);
        }
    }
}
