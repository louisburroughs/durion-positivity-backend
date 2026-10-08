package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxTypesResponse.RegimeEntry;
import com.positivity.tax.common.validation.TaxTypeCodes;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.config.TaxProperties.CountryProfile;
import com.positivity.tax.internal.config.TaxProperties.RegistrationFormat;
import com.positivity.tax.internal.service.TaxCountryProfiles.CountryTaxProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * The registration-number shape stub (CAP:550 S32b): {@link #wellFormed(String, String)}, answered from
 * {@code pos.tax.registration.formats}.
 * <p>
 * A shape is a security control (ADR-0072 Decision 1, Security conditions (a) and (b) on durion#571):
 * <ul>
 *   <li>Nothing passes by default. A regime without a shape answers {@code false} for every number, and a
 *       regime that a country profile declares without a shape fails startup.</li>
 *   <li>A shape must contain a letter, so no shape can match bare digits (the nine-digit SSN, SIN, EIN and
 *       ITIN form). A shape with no letter fails startup.</li>
 *   <li>Shapes are the service's shipped configuration, read once at startup and changed only by a
 *       reviewed commit: never per tenant, by a tenant, by a tax provider or at runtime. Nothing here
 *       exposes a way to change them.</li>
 * </ul>
 * This class never logs, stores or returns a number. The shipped shapes are placeholders held for
 * expert advice (OI-4); no regime is named in code.
 */
@Component
public class RegistrationNumberShapes {

    /** The template character that stands for one digit. */
    public static final char DIGIT = '#';

    /** The longest shape: a normalised number is stored in at most 32 characters. */
    static final int MAX_SHAPE_LENGTH = 32;

    private static final String REGISTRATION = "pos.tax.registration";
    private static final String FORMATS = REGISTRATION + ".formats";
    private static final String COUNTRIES = "pos.tax.countries.";

    private final Map<String, String> shapes;
    private final Map<String, String> supplierRegimes;

    /**
     * The startup check, run by Spring: the shapes must come only from the service's shipped
     * configuration, then pass {@link #RegistrationNumberShapes(TaxProperties, TaxCountryProfiles)}.
     *
     * @param properties  the bound tax properties
     * @param profiles    the validated country profiles
     * @param environment the environment the properties were bound from
     */
    @Autowired
    public RegistrationNumberShapes(
            @NonNull TaxProperties properties,
            @NonNull TaxCountryProfiles profiles,
            @NonNull ConfigurableEnvironment environment) {
        this(requireShippedSource(properties, environment), profiles);
    }

    /**
     * The configuration check alone, for a caller that has bound the properties itself.
     *
     * @param properties the tax properties
     * @param profiles   the validated country profiles
     */
    RegistrationNumberShapes(@NonNull TaxProperties properties, @NonNull TaxCountryProfiles profiles) {
        this.shapes = validateShapes(properties.getRegistration().getFormats());
        requireShapeForEveryDeclaredRegime(profiles, shapes);
        this.supplierRegimes = validateSupplierRegimes(properties.getCountries(), profiles);
    }

    /**
     * Whether {@code number} matches the configured shape of {@code regime}.
     * <p>
     * The number is trimmed and upper-cased, and spaces and hyphens are removed; it must then equal the
     * shape character for character, {@code #} matching one ASCII digit and a letter matching itself. A
     * regime without a shape, a {@code null} or blank number all answer {@code false}.
     *
     * @param regime the regime code; may be {@code null}
     * @param number the number as entered; may be {@code null}. Never logged
     * @return {@code true} only when the number matches the regime's shape
     */
    public boolean wellFormed(@Nullable String regime, @Nullable String number) {
        String shape = regime == null ? null : shapes.get(regime);
        if (shape == null || number == null) {
            return false;
        }
        String normalized = normalize(number);
        if (normalized.length() != shape.length()) {
            return false;
        }
        for (int i = 0; i < shape.length(); i++) {
            char expected = shape.charAt(i);
            char actual = normalized.charAt(i);
            boolean matches = expected == DIGIT ? actual >= '0' && actual <= '9' : actual == expected;
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    /**
     * The normalised form of a number: trimmed, ASCII letters upper-cased, spaces and hyphens removed. A
     * caller stores this form once {@link #wellFormed} accepts it.
     *
     * @param number the number as entered
     * @return the normalised number
     */
    @NonNull
    public static String normalize(@NonNull String number) {
        String trimmed = number.trim();
        StringBuilder normalized = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == ' ' || c == '-') {
                continue;
            }
            normalized.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
        }
        return normalized.toString();
    }

    /**
     * The regime whose shape a supplier's number must match in {@code countryCode}, when the country
     * names one ({@code pos.tax.countries.<country>.supplier-registration-regime}).
     *
     * @param countryCode an upper-case country code
     * @return the regime, or empty when the country accepts no supplier number
     */
    @NonNull
    public Optional<String> supplierRegime(@NonNull String countryCode) {
        return Optional.ofNullable(supplierRegimes.get(countryCode));
    }

    // ---------------------------------------------------------------------------------------
    // Startup check
    // ---------------------------------------------------------------------------------------

    /**
     * Refuses a shape set anywhere but the service's shipped configuration: an environment variable, a
     * system property, a command-line argument or an external file could change a security control
     * without a reviewed commit (Security clarification B on durion#571). Only a classpath
     * {@code application*.yml} resource, which ships inside the service, may set {@code pos.tax.registration}.
     *
     * @param properties  the bound properties, returned unchanged
     * @param environment the environment
     * @return {@code properties}
     */
    @NonNull
    static TaxProperties requireShippedSource(
            @NonNull TaxProperties properties, @NonNull ConfigurableEnvironment environment) {
        ConfigurationPropertyName registration = ConfigurationPropertyName.of(REGISTRATION);
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (isShipped(source)) {
                continue;
            }
            for (ConfigurationPropertySource adapted : ConfigurationPropertySources.from(source)) {
                if (adapted.containsDescendantOf(registration) == ConfigurationPropertyState.PRESENT) {
                    throw invalid(
                            REGISTRATION,
                            "may be set only in the service's shipped application configuration, never by "
                                    + source.getName());
                }
            }
        }
        return properties;
    }

    private static boolean isShipped(@NonNull PropertySource<?> source) {
        String name = source.getName();
        return name.contains("'class path resource [application") && name.endsWith("'")
                || "configurationProperties".equals(name);
    }

    @NonNull
    private static Map<String, String> validateShapes(@NonNull List<RegistrationFormat> formats) {
        Map<String, String> validated = new LinkedHashMap<>();
        for (int i = 0; i < formats.size(); i++) {
            String property = FORMATS + "[" + i + "]";
            RegistrationFormat format = formats.get(i) == null ? new RegistrationFormat() : formats.get(i);
            String regime = format.getRegime();
            if (!TaxTypeCodes.isWellFormed(regime)) {
                throw invalid(
                        property + ".regime",
                        "'" + regime + "' is not a regime code of 1 to 32 upper-case letters, digits or underscores");
            }
            if (validated.containsKey(regime)) {
                throw invalid(property + ".regime", "regime " + regime + " has a shape already");
            }
            validated.put(regime, validateShape(property + ".shape", regime, format.getShape()));
        }
        return Map.copyOf(validated);
    }

    @NonNull
    private static String validateShape(@NonNull String property, @NonNull String regime, @Nullable String shape) {
        if (shape == null || shape.isBlank()) {
            throw invalid(property, "regime " + regime + " has no shape");
        }
        if (shape.length() > MAX_SHAPE_LENGTH) {
            throw invalid(property, "the shape of regime " + regime + " is longer than " + MAX_SHAPE_LENGTH);
        }
        boolean hasLetter = false;
        for (int i = 0; i < shape.length(); i++) {
            char c = shape.charAt(i);
            boolean letter = c >= 'A' && c <= 'Z';
            if (c != DIGIT && !letter) {
                throw invalid(
                        property,
                        "the shape of regime " + regime + " may contain only '#' (one digit) and the letters A-Z");
            }
            hasLetter |= letter;
        }
        if (!hasLetter) {
            throw invalid(
                    property,
                    "the shape of regime " + regime + " contains no letter, so it could match bare digits"
                            + " (ADR-0072 Decision 1, condition (b))");
        }
        return shape;
    }

    private static void requireShapeForEveryDeclaredRegime(
            @NonNull TaxCountryProfiles profiles, @NonNull Map<String, String> shapes) {
        for (CountryTaxProfile profile : profiles.all().values()) {
            List<RegimeEntry> regimes = profile.regimes();
            for (int r = 0; r < regimes.size(); r++) {
                String regime = regimes.get(r).regime();
                if (!shapes.containsKey(regime)) {
                    throw invalid(
                            FORMATS,
                            "no shape is configured for regime " + regime + ", declared under " + COUNTRIES
                                    + profile.countryCode() + ".regimes[" + r + "]");
                }
            }
        }
    }

    @NonNull
    private static Map<String, String> validateSupplierRegimes(
            @NonNull Map<String, CountryProfile> countries, @NonNull TaxCountryProfiles profiles) {
        Map<String, String> validated = new LinkedHashMap<>();
        countries.forEach((country, source) -> {
            String regime = source == null ? null : source.getSupplierRegistrationRegime();
            if (regime == null || regime.isBlank()) {
                return;
            }
            String code = regime.trim();
            boolean declared = profiles.profile(country)
                    .map(profile -> profile.regimes().stream()
                            .anyMatch(entry -> entry.regime().equals(code)))
                    .orElse(false);
            if (!declared) {
                throw invalid(
                        COUNTRIES + country + ".supplier-registration-regime",
                        "regime " + code + " is not declared under " + COUNTRIES + country + ".regimes");
            }
            validated.put(country.toUpperCase(Locale.ROOT), code);
        });
        return Map.copyOf(validated);
    }

    @NonNull
    private static IllegalStateException invalid(@NonNull String property, @NonNull String reason) {
        return new IllegalStateException("Invalid tax configuration " + property + ": " + reason);
    }
}
