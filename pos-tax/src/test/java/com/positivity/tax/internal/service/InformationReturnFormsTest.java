package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.InformationReturnFormsResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The information-return forms stub (CAP:550 #2615, AC16): fixture configuration for the made-up country {@code ZZ}
 * (not tax law), an unconfigured country, every startup refusal naming its property, and the shipped placeholders.
 */
@DisplayName("InformationReturnForms — the per-country information-return stub (#2615)")
class InformationReturnFormsTest {

    private static final String ZZ = "pos.tax.information-returns.ZZ.forms";

    /** The fixture forms of ZZ: ZZ_FORM_A (boxes 1, 2; two schemes) and ZZ_FORM_B (box 7; one scheme). */
    private static Map<String, String> fixture() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(ZZ + "[0].code", "ZZ_FORM_A");
        p.put(ZZ + "[0].label", "Form A");
        p.put(ZZ + "[0].boxes[0].code", "1");
        p.put(ZZ + "[0].boxes[0].label", "Box one");
        p.put(ZZ + "[0].boxes[1].code", "2");
        p.put(ZZ + "[0].boxes[1].label", "Box two");
        p.put(ZZ + "[0].payee-id-schemes[0]", "ZZ_BUSINESS_ID");
        p.put(ZZ + "[0].payee-id-schemes[1]", "ZZ_PERSON_ID");
        p.put(ZZ + "[1].code", "ZZ_FORM_B");
        p.put(ZZ + "[1].label", "Form B");
        p.put(ZZ + "[1].boxes[0].code", "7");
        p.put(ZZ + "[1].boxes[0].label", "Box seven");
        p.put(ZZ + "[1].payee-id-schemes[0]", "ZZ_BUSINESS_ID");
        return p;
    }

    private static InformationReturnForms forms(Map<String, String> properties) {
        return new InformationReturnForms(TaxProfileFixtures.bind(properties));
    }

    private static Map<String, String> with(String key, String value) {
        Map<String, String> p = fixture();
        if (value == null) {
            p.remove(key);
        } else {
            p.put(key, value);
        }
        return p;
    }

    @Test
    @DisplayName("ZZ answers its forms, boxes and schemes in configured order with source STUB")
    void fixtureCountry() {
        InformationReturnFormsResponse zz = forms(fixture()).read("ZZ");

        assertThat(zz.countryCode()).isEqualTo("ZZ");
        assertThat(zz.source()).isEqualTo("STUB");
        assertThat(zz.forms())
                .extracting(InformationReturnFormsResponse.FormEntry::form)
                .containsExactly("ZZ_FORM_A", "ZZ_FORM_B");
        assertThat(zz.forms().getFirst().boxes())
                .extracting(
                        InformationReturnFormsResponse.BoxEntry::box, InformationReturnFormsResponse.BoxEntry::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", "Box one"),
                        org.assertj.core.groups.Tuple.tuple("2", "Box two"));
        assertThat(zz.forms().getFirst().payeeIdSchemes()).containsExactly("ZZ_BUSINESS_ID", "ZZ_PERSON_ID");
    }

    @Test
    @DisplayName("a country without configuration (MX) answers an empty list")
    void unconfiguredCountry() {
        InformationReturnFormsResponse mx = forms(fixture()).read("MX");

        assertThat(mx.countryCode()).isEqualTo("MX");
        assertThat(mx.source()).isEqualTo("STUB");
        assertThat(mx.forms()).isEmpty();
    }

    @Test
    @DisplayName("startup fails naming the property: a form without boxes, a repeated box or form, a bad scheme, a"
            + " lower-case country key, a bad code or label")
    void startupRefusals() {
        Map<String, String> noBoxes = fixture();
        noBoxes.keySet().removeIf(key -> key.startsWith(ZZ + "[1].boxes"));
        assertRefused(noBoxes, ZZ + "[1].boxes");
        assertRefused(with(ZZ + "[0].boxes[1].code", "1"), ZZ + "[0].boxes[1].code");
        assertRefused(with(ZZ + "[1].code", "ZZ_FORM_A"), ZZ + "[1].code");
        assertRefused(with(ZZ + "[0].payee-id-schemes[0]", "ssn"), ZZ + "[0].payee-id-schemes[0]");
        assertRefused(with(ZZ + "[0].payee-id-schemes[0]", "SSN1"), ZZ + "[0].payee-id-schemes[0]");
        assertRefused(with(ZZ + "[0].payee-id-schemes[1]", "ZZ_BUSINESS_ID"), ZZ + "[0].payee-id-schemes[1]");
        assertRefused(with(ZZ + "[0].code", "zz_form_a"), ZZ + "[0].code");
        assertRefused(with(ZZ + "[0].boxes[0].code", "1-A"), ZZ + "[0].boxes[0].code");
        assertRefused(with(ZZ + "[0].label", " "), ZZ + "[0].label");
        assertRefused(with(ZZ + "[0].boxes[0].label", "x".repeat(101)), ZZ + "[0].boxes[0].label");

        Map<String, String> lowerCaseKey = new LinkedHashMap<>();
        lowerCaseKey.put("pos.tax.information-returns.Us.forms[0].code", "US_FORM");
        lowerCaseKey.put("pos.tax.information-returns.Us.forms[0].label", "Form");
        lowerCaseKey.put("pos.tax.information-returns.Us.forms[0].boxes[0].code", "1");
        lowerCaseKey.put("pos.tax.information-returns.Us.forms[0].boxes[0].label", "Box");
        assertRefused(lowerCaseKey, "pos.tax.information-returns.Us");
    }

    private static void assertRefused(Map<String, String> properties, String property) {
        assertThatThrownBy(() -> forms(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid tax configuration " + property);
    }

    @Test
    @DisplayName("the shipped configuration loads and answers the US and CA placeholder rows; box 020 stays text")
    void shippedConfiguration() throws Exception {
        List<PropertySource<?>> yaml =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        TaxProperties shipped = new Binder(
                        ConfigurationPropertySources.from(yaml), new PropertySourcesPlaceholdersResolver(yaml))
                .bind("pos.tax", TaxProperties.class)
                .get();

        InformationReturnForms forms = new InformationReturnForms(shipped);

        assertThat(forms.read("US").forms())
                .extracting(InformationReturnFormsResponse.FormEntry::form)
                .containsExactly("US_1099_NEC", "US_1099_MISC");
        assertThat(forms.read("US").forms().get(1).boxes())
                .extracting(InformationReturnFormsResponse.BoxEntry::box)
                .containsExactly("1", "2", "3", "6", "10");
        assertThat(forms.read("CA").forms()).singleElement().satisfies(form -> {
            assertThat(form.form()).isEqualTo("CA_T4A");
            assertThat(form.boxes())
                    .extracting(InformationReturnFormsResponse.BoxEntry::box)
                    .containsExactly("020", "048");
            assertThat(form.payeeIdSchemes()).containsExactly("BN", "SIN");
        });
    }
}
