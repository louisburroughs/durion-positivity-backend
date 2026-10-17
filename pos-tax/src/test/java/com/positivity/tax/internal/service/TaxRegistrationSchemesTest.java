package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.InformationReturnFormsResponse;
import com.positivity.tax.internal.dto.TaxRegistrationSchemesResponse;
import com.positivity.tax.internal.dto.TaxRegistrationSchemesResponse.SchemeEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The registration-schemes stub (CAP:550 #2623, AC 1, 2 and 10): fixture configuration for the made-up country
 * {@code ZZ} (not tax law), an unconfigured country, every startup refusal naming its property, the shipped
 * placeholders, and the alignment of the information-return forms' payee-id schemes with the vocabulary.
 */
@DisplayName("TaxRegistrationSchemes — the closed vocabulary of tax-registration schemes (#2623)")
class TaxRegistrationSchemesTest {

    private static final String ZZ = "pos.tax.registration-schemes.ZZ";
    private static final String QZ = "pos.tax.registration-schemes.QZ";

    /** ZZ: a business scheme, a person scheme and a regional one (two regions); QZ: one business scheme. */
    private static Map<String, String> fixture() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(ZZ + "[0].scheme", "ZZ_BUSINESS_ID");
        p.put(ZZ + "[0].label", "Business id");
        p.put(ZZ + "[0].holder-type", "BUSINESS");
        p.put(ZZ + "[0].regional", "false");
        p.put(ZZ + "[1].scheme", "ZZ_PERSON_ID");
        p.put(ZZ + "[1].label", "Person id");
        p.put(ZZ + "[1].holder-type", "PERSON");
        p.put(ZZ + "[1].regional", "false");
        p.put(ZZ + "[2].scheme", "ZZ_REGIONAL");
        p.put(ZZ + "[2].label", "Regional registration");
        p.put(ZZ + "[2].holder-type", "BUSINESS");
        p.put(ZZ + "[2].regional", "true");
        p.put(ZZ + "[2].regions[0]", "QC");
        p.put(ZZ + "[2].regions[1]", "ON");
        p.put(QZ + "[0].scheme", "QZ_BUSINESS_ID");
        p.put(QZ + "[0].label", "Business id");
        p.put(QZ + "[0].holder-type", "BUSINESS");
        p.put(QZ + "[0].regional", "false");
        return p;
    }

    private static TaxRegistrationSchemes schemes(Map<String, String> properties) {
        return new TaxRegistrationSchemes(TaxProfileFixtures.bind(properties));
    }

    private static Map<String, String> with(String key, String value) {
        Map<String, String> p = fixture();
        p.put(key, value);
        return p;
    }

    @Test
    @DisplayName("ZZ answers its schemes by code with holder type and regions, source STUB; no country answers all")
    void fixtureCountry() {
        TaxRegistrationSchemes stub = schemes(fixture());
        TaxRegistrationSchemesResponse zz = stub.read("ZZ");
        assertThat(zz.source()).isEqualTo("STUB");
        assertThat(zz.schemes())
                .extracting(
                        SchemeEntry::scheme, SchemeEntry::countryCode, SchemeEntry::holderType, SchemeEntry::regional)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ZZ_BUSINESS_ID", "ZZ", "BUSINESS", false),
                        org.assertj.core.groups.Tuple.tuple("ZZ_PERSON_ID", "ZZ", "PERSON", false),
                        org.assertj.core.groups.Tuple.tuple("ZZ_REGIONAL", "ZZ", "BUSINESS", true));
        assertThat(zz.schemes().get(2).regions()).containsExactly("QC", "ON");
        assertThat(zz.schemes().get(0).regions()).isEmpty();
        assertThat(zz.schemes().get(0).label()).isEqualTo("Business id");

        TaxRegistrationSchemesResponse all = stub.read(null);
        assertThat(all.schemes())
                .extracting(SchemeEntry::countryCode, SchemeEntry::scheme)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("QZ", "QZ_BUSINESS_ID"),
                        org.assertj.core.groups.Tuple.tuple("ZZ", "ZZ_BUSINESS_ID"),
                        org.assertj.core.groups.Tuple.tuple("ZZ", "ZZ_PERSON_ID"),
                        org.assertj.core.groups.Tuple.tuple("ZZ", "ZZ_REGIONAL"));
    }

    @Test
    @DisplayName("a country without configuration (MX) answers an empty list")
    void unconfiguredCountry() {
        TaxRegistrationSchemesResponse mx = schemes(fixture()).read("MX");
        assertThat(mx.source()).isEqualTo("STUB");
        assertThat(mx.schemes()).isEmpty();
    }

    @Test
    @DisplayName("startup fails naming the property: a code repeated across countries, a digit in a code or a region,"
            + " a regional scheme without regions, regions on a non-regional scheme, a bad holder type, label or key")
    void startupRefusals() {
        // AC 2: the same code under two countries.
        assertRefused(with(QZ + "[0].scheme", "ZZ_BUSINESS_ID"), QZ + "[0].scheme");
        // AC 2: a digit never enters a code or a region.
        assertRefused(with(ZZ + "[0].scheme", "GST2"), ZZ + "[0].scheme");
        assertRefused(with(ZZ + "[2].regions[0]", "75C"), ZZ + "[2].regions[0]");
        assertRefused(with(ZZ + "[0].scheme", "ein"), ZZ + "[0].scheme");
        assertRefused(with(ZZ + "[0].scheme", "E"), ZZ + "[0].scheme");
        assertRefused(with(ZZ + "[2].regions[0]", "qc"), ZZ + "[2].regions[0]");
        assertRefused(with(ZZ + "[2].regions[0]", "ABCD"), ZZ + "[2].regions[0]");
        assertRefused(with(ZZ + "[2].regions[1]", "QC"), ZZ + "[2].regions[1]");
        // AC 2: regionality and regions agree.
        Map<String, String> noRegions = fixture();
        noRegions.keySet().removeIf(key -> key.startsWith(ZZ + "[2].regions"));
        assertRefused(noRegions, ZZ + "[2].regions");
        assertRefused(with(ZZ + "[0].regions[0]", "QC"), ZZ + "[0].regions");
        assertRefused(with(ZZ + "[0].holder-type", "COMPANY"), ZZ + "[0].holder-type");
        assertRefused(with(ZZ + "[0].label", " "), ZZ + "[0].label");
        assertRefused(with(ZZ + "[0].label", "x".repeat(101)), ZZ + "[0].label");
        Map<String, String> noRegional = fixture();
        noRegional.remove(ZZ + "[0].regional");
        assertRefused(noRegional, ZZ + "[0].regional");
        Map<String, String> lowerCaseKey = new LinkedHashMap<>();
        lowerCaseKey.put("pos.tax.registration-schemes.Us[0].scheme", "EIN");
        lowerCaseKey.put("pos.tax.registration-schemes.Us[0].label", "Employer id");
        lowerCaseKey.put("pos.tax.registration-schemes.Us[0].holder-type", "BUSINESS");
        lowerCaseKey.put("pos.tax.registration-schemes.Us[0].regional", "false");
        assertRefused(lowerCaseKey, "pos.tax.registration-schemes.Us");
    }

    private static void assertRefused(Map<String, String> properties, String property) {
        assertThatThrownBy(() -> schemes(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid tax configuration " + property);
    }

    @Test
    @DisplayName("the shipped configuration loads and answers the US and CA placeholder rows (AC 1)")
    void shippedConfiguration() throws Exception {
        TaxRegistrationSchemes stub = new TaxRegistrationSchemes(shipped());
        assertThat(stub.read("US").schemes())
                .extracting(SchemeEntry::scheme, SchemeEntry::holderType, SchemeEntry::regional)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("EIN", "BUSINESS", false),
                        org.assertj.core.groups.Tuple.tuple("ITIN", "PERSON", false),
                        org.assertj.core.groups.Tuple.tuple("SSN", "PERSON", false));
        assertThat(stub.read("CA").schemes())
                .extracting(SchemeEntry::scheme, SchemeEntry::holderType, SchemeEntry::regional)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("BN", "BUSINESS", false),
                        org.assertj.core.groups.Tuple.tuple("GST_HST", "BUSINESS", false),
                        org.assertj.core.groups.Tuple.tuple("QST", "BUSINESS", true),
                        org.assertj.core.groups.Tuple.tuple("SIN", "PERSON", false));
        assertThat(stub.read("CA").schemes().stream()
                        .filter(s -> s.scheme().equals("QST"))
                        .findFirst()
                        .orElseThrow()
                        .regions())
                .containsExactly("QC");
        assertThat(stub.read(null).schemes()).hasSize(7);
        assertThat(stub.read("MX").schemes()).isEmpty();
    }

    @Test
    @DisplayName("alignment (rule 11, AC 10): every payee-id scheme of the information-return forms stub is a listed"
            + " scheme of the same country")
    void informationReturnPayeeIdSchemesAreListedSchemes() throws Exception {
        TaxProperties shipped = shipped();
        TaxRegistrationSchemes stub = new TaxRegistrationSchemes(shipped);
        InformationReturnForms forms = new InformationReturnForms(shipped);
        for (String country : shipped.getInformationReturns().keySet()) {
            Set<String> listed = stub.read(country).schemes().stream()
                    .map(SchemeEntry::scheme)
                    .collect(Collectors.toSet());
            for (InformationReturnFormsResponse.FormEntry form :
                    forms.read(country).forms()) {
                assertThat(listed)
                        .as("%s payee-id schemes of %s", country, form.form())
                        .containsAll(form.payeeIdSchemes());
            }
        }
    }

    private static TaxProperties shipped() throws Exception {
        List<PropertySource<?>> yaml =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(yaml), new PropertySourcesPlaceholdersResolver(yaml))
                .bind("pos.tax", TaxProperties.class)
                .get();
    }
}
