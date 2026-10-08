package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tax.common.dto.TaxTypesResponse.RegimeEntry;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.service.TaxCountryProfiles.CountryTaxProfile;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * CAP:550 S32a AC 1: the per-country profile startup check. Each invalid entry fails startup with a
 * message naming the offending property; the shipped configuration passes and ships no rate.
 */
@DisplayName("TaxCountryProfiles startup check (CAP:550 S32a)")
class TaxCountryProfilesTest {

    private static final String CA = "pos.tax.countries.CA.";

    private static Map<String, String> fixtureWith(Consumer<Map<String, String>> change) {
        Map<String, String> properties = new LinkedHashMap<>(TaxProfileFixtures.FIRST_COUNTRY);
        change.accept(properties);
        return properties;
    }

    static Stream<Arguments> invalidProfiles() {
        return Stream.of(
                Arguments.of(
                        "country code not ISO 3166-1 alpha-2",
                        fixtureWith(p -> p.put("pos.tax.countries.C1.currency", "CAD")),
                        "pos.tax.countries.C1"),
                Arguments.of(
                        "lower-case country key, as an environment variable binds it",
                        lowerCaseCountryKeys(),
                        "pos.tax.countries.ca"),
                Arguments.of("currency not ISO 4217", fixtureWith(p -> p.put(CA + "currency", "CAX")), CA + "currency"),
                Arguments.of("currency missing", fixtureWith(p -> p.remove(CA + "currency")), CA + "currency"),
                Arguments.of(
                        "tax-type code not 1-32 upper-case letters, digits or underscores",
                        fixtureWith(p -> p.put(CA + "tax-types[3].code", "pst")),
                        CA + "tax-types[3].code"),
                Arguments.of(
                        "tax-type code missing",
                        fixtureWith(p -> p.remove(CA + "tax-types[3].code")),
                        CA + "tax-types[3].code"),
                Arguments.of(
                        "tax type declared twice",
                        fixtureWith(p -> p.put(CA + "tax-types[3].code", "GST")),
                        CA + "tax-types[3].code"),
                Arguments.of(
                        "regime code malformed",
                        fixtureWith(p -> p.put(CA + "regimes[1].code", "Q-ST")),
                        CA + "regimes[1].code"),
                Arguments.of(
                        "regime declared twice",
                        fixtureWith(p -> p.put(CA + "regimes[1].code", "GST_HST")),
                        CA + "regimes[1].code"),
                Arguments.of(
                        "tax type names an undeclared regime",
                        fixtureWith(p -> p.put(CA + "tax-types[3].regime", "NO_SUCH_REGIME")),
                        CA + "tax-types[3].regime"),
                Arguments.of(
                        "rate-row region code not 1-3 letters or digits",
                        fixtureWith(p -> p.put(CA + "rates[5].region-code", "ONTA")),
                        CA + "rates[5].region-code"),
                Arguments.of(
                        "regime region code not 1-3 letters or digits",
                        fixtureWith(p -> p.put(CA + "regimes[1].regions[0]", "Q-C")),
                        CA + "regimes[1].regions[0]"),
                Arguments.of(
                        "rate row names a tax type the country does not declare",
                        fixtureWith(p -> p.put(CA + "tax-types[1].code", "XST")),
                        CA + "rates[5].tax-type"),
                Arguments.of(
                        "rate equal to 1", fixtureWith(p -> p.put(CA + "rates[5].rate", "1")), CA + "rates[5].rate"),
                Arguments.of(
                        "rate below 0", fixtureWith(p -> p.put(CA + "rates[5].rate", "-0.01")), CA + "rates[5].rate"),
                Arguments.of(
                        "effective-from missing",
                        fixtureWith(p -> p.remove(CA + "rates[5].effective-from")),
                        CA + "rates[5].effective-from"),
                Arguments.of(
                        "effective-to before effective-from",
                        fixtureWith(p -> p.put(CA + "rates[5].effective-to", "2019-12-31")),
                        CA + "rates[5].effective-to"),
                Arguments.of(
                        "two rows of one region and tax type in effect on the same date",
                        fixtureWith(p -> p.put(CA + "rates[0].effective-to", "2026-07-01")),
                        CA + "rates[1]"),
                Arguments.of(
                        "two rows of one region and regime in effect on the same date (one rate per regime)",
                        fixtureWith(p -> TaxProfileFixtures.row(p, CA, 6, "ON", "GST", "0.011", "2020-01-01", null)),
                        CA + "rates[6]"),
                Arguments.of(
                        "input-tax-recoverable missing for a declared tax type",
                        fixtureWith(p -> p.remove(CA + "tax-types[3].input-tax-recoverable")),
                        CA + "tax-types[3].input-tax-recoverable"),
                Arguments.of(
                        "jurisdiction type unknown",
                        fixtureWith(p -> p.put(CA + "tax-types[3].jurisdiction-type", "GALAXY")),
                        CA + "tax-types[3].jurisdiction-type"),
                Arguments.of(
                        "default-providers names an unknown plug-in",
                        fixtureWith(p -> p.put("pos.tax.default-providers.CA", "NO_SUCH_PLUGIN")),
                        "pos.tax.default-providers.CA"),
                Arguments.of(
                        "default-providers routes a country to another country's plug-in",
                        fixtureWith(p -> p.put("pos.tax.default-providers.US", "CA_SELF")),
                        "pos.tax.default-providers.US"),
                Arguments.of(
                        "default-providers key not ISO 3166-1 alpha-2",
                        fixtureWith(p -> p.put("pos.tax.default-providers.C1", "C1_SELF")),
                        "pos.tax.default-providers.C1"),
                Arguments.of(
                        "default-providers entry for a country with no profile",
                        fixtureWith(p -> p.put("pos.tax.default-providers.ZZ", "ZZ_SELF")),
                        "pos.tax.default-providers.ZZ"),
                Arguments.of(
                        "a profiled country with no default provider (it would fall through to the switch)",
                        fixtureWith(p -> p.remove("pos.tax.default-providers.CA")),
                        "pos.tax.default-providers.CA"));
    }

    /** The first-country fixture with its country keys lower-cased, as environment-variable binding produces. */
    private static Map<String, String> lowerCaseCountryKeys() {
        Map<String, String> lowered = new LinkedHashMap<>();
        TaxProfileFixtures.FIRST_COUNTRY.forEach((key, value) -> lowered.put(
                key.replace("pos.tax.countries.CA.", "pos.tax.countries.ca.")
                        .replace("pos.tax.default-providers.CA", "pos.tax.default-providers.ca"),
                value));
        return lowered;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidProfiles")
    @DisplayName("an invalid profile fails startup, naming the property")
    void invalidProfileFailsStartup(String label, Map<String, String> properties, String property) {
        TaxProperties bound = TaxProfileFixtures.bind(properties);

        assertThatThrownBy(() -> new TaxCountryProfiles(bound))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid tax configuration " + property + ":");
    }

    @Test
    @DisplayName("the first-country fixture passes and is indexed by region, tax type and date")
    void validFixtureIsIndexed() {
        TaxCountryProfiles profiles = new TaxCountryProfiles(TaxProfileFixtures.bind(TaxProfileFixtures.FIRST_COUNTRY));

        CountryTaxProfile profile = profiles.profile("ca").orElseThrow();
        assertThat(profile.currencyExponent()).isEqualTo(2);
        assertThat(profile.taxTypes())
                .extracting(t -> t.taxType())
                .containsExactlyInAnyOrder("GST", "HST", "QST", "PST");
        assertThat(profile.taxTypes())
                .filteredOn(t -> "PST".equals(t.taxType()))
                .singleElement()
                .satisfies(t -> assertThat(t.regime()).isNull());
        assertThat(profile.regimes()).extracting(r -> r.regime()).containsExactlyInAnyOrder("GST_HST", "QST");
        assertThat(profile.regimes())
                .filteredOn(r -> r.regime().equals("GST_HST"))
                .singleElement()
                .satisfies(r -> assertThat(r.regions()).isEmpty());
        assertThat(profiles.defaultProvider("CA")).contains("CA_SELF");
        assertThat(profiles.defaultProvider("US")).isEmpty();
        // Inclusive ends: the GST rate changes on 2026-07-01.
        assertThat(profile.ratesInEffect("bc", LocalDate.parse("2026-06-30")))
                .extracting(r -> r.rate().toPlainString())
                .containsExactlyInAnyOrder("0.011", "0.022");
        assertThat(profile.ratesInEffect("BC", LocalDate.parse("2026-07-01")))
                .extracting(r -> r.rate().toPlainString())
                .containsExactlyInAnyOrder("0.012", "0.022");
        assertThat(profile.ratesInEffect("BC", LocalDate.parse("2019-12-31"))).isEmpty();
        assertThat(profile.ratesInEffect(null, LocalDate.parse("2026-07-01"))).isEmpty();
    }

    @Test
    @DisplayName("a made-up country is configuration only: it validates and indexes with no code change")
    void madeUpCountryIsConfigurationOnly() {
        TaxCountryProfiles profiles =
                new TaxCountryProfiles(TaxProfileFixtures.bind(TaxProfileFixtures.MADE_UP_COUNTRY));

        CountryTaxProfile profile = profiles.profile("ZZ").orElseThrow();
        assertThat(profile.currencyExponent()).isZero();
        assertThat(profile.taxTypes().get(0).jurisdictionType()).isEqualTo(TaxJurisdictionType.COUNTRY);
        assertThat(profiles.defaultProvider("ZZ")).contains("ZZ_SELF");
        // The "[...]" map-key notation keeps the "_" in a configured code.
        assertThat(profile.taxTypes())
                .singleElement()
                .satisfies(t -> assertThat(t.taxType()).isEqualTo("ZZ_LEVY"));
    }

    @Test
    @DisplayName("the shipped configuration passes the startup check and ships no rate for any country")
    void shippedConfigurationShipsNoRate() throws Exception {
        List<PropertySource<?>> yaml =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        TaxProperties shipped = new Binder(
                        ConfigurationPropertySources.from(yaml), new PropertySourcesPlaceholdersResolver(yaml))
                .bind("pos.tax", TaxProperties.class)
                .get();

        TaxCountryProfiles profiles = new TaxCountryProfiles(shipped);

        assertThat(profiles.all()).isNotEmpty();
        assertThat(profiles.all().values())
                .allSatisfy(profile -> assertThat(profile.rates())
                        .as("no rate ships for %s (OI-4)", profile.countryCode())
                        .isEmpty());
        // Every routed country has a profile.
        assertThat(shipped.getDefaultProviders())
                .allSatisfy((country, plugin) ->
                        assertThat(profiles.profile(country)).isPresent());
        profiles.all()
                .values()
                .forEach(profile -> assertThat(profile.taxTypes()).isNotEmpty());
        // Codes are list fields, so a code with "_" binds intact (a map key would lose the "_").
        assertThat(profiles.all().values())
                .flatExtracting(CountryTaxProfile::regimes)
                .extracting(RegimeEntry::regime)
                .anySatisfy(code -> assertThat(code).contains("_"));
    }
}
