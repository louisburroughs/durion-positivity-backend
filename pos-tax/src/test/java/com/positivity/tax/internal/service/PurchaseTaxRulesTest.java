package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.PurchaseTaxRulesResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * The purchase-tax rules stub (CAP:550 S43, AC 12): fixture configuration for the made-up country {@code ZZ} (not tax
 * law), an unconfigured country, every startup refusal naming its property, and the shipped placeholder.
 */
@DisplayName("PurchaseTaxRules — the per-country purchase-tax stub (S43)")
class PurchaseTaxRulesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
    private static final String ZZ = "pos.tax.purchase-rules.ZZ";

    private static Map<String, String> fixture() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(ZZ + ".tax-on-resale-goods", "HOLD");
        p.put(ZZ + ".self-assess-untaxed-expenses", "true");
        p.put("pos.tax.purchase-rules.QM.tax-on-resale-goods", "ALLOW");
        p.put("pos.tax.purchase-rules.QM.self-assess-untaxed-expenses", "false");
        return p;
    }

    private static PurchaseTaxRules rules(Map<String, String> properties) {
        return new PurchaseTaxRules(TaxProfileFixtures.bind(properties), CLOCK);
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
    @DisplayName("ZZ answers configured HOLD/true with source STUB, on the date asked or today")
    void fixtureCountry() {
        PurchaseTaxRulesResponse zz = rules(fixture()).read("ZZ", LocalDate.of(2026, 9, 30));

        assertThat(zz.countryCode()).isEqualTo("ZZ");
        assertThat(zz.asOf()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(zz.source()).isEqualTo("STUB");
        assertThat(zz.configured()).isTrue();
        assertThat(zz.taxOnResaleGoods()).isEqualTo("HOLD");
        assertThat(zz.selfAssessUntaxedExpenses()).isTrue();

        PurchaseTaxRulesResponse qm = rules(fixture()).read("QM", null);
        assertThat(qm.asOf()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(qm.configured()).isTrue();
        assertThat(qm.taxOnResaleGoods()).isEqualTo("ALLOW");
        assertThat(qm.selfAssessUntaxedExpenses()).isFalse();
    }

    @Test
    @DisplayName("a country without rules (MX) answers configured false, ALLOW, false: a defined answer")
    void unconfiguredCountry() {
        PurchaseTaxRulesResponse mx = rules(fixture()).read("MX", null);

        assertThat(mx.countryCode()).isEqualTo("MX");
        assertThat(mx.source()).isEqualTo("STUB");
        assertThat(mx.configured()).isFalse();
        assertThat(mx.taxOnResaleGoods()).isEqualTo("ALLOW");
        assertThat(mx.selfAssessUntaxedExpenses()).isFalse();
    }

    @Test
    @DisplayName("startup fails naming the property: a key Us, a value BLOCK, a missing value")
    void startupRefusals() {
        Map<String, String> lowerCaseKey = new LinkedHashMap<>();
        lowerCaseKey.put("pos.tax.purchase-rules.Us.tax-on-resale-goods", "HOLD");
        lowerCaseKey.put("pos.tax.purchase-rules.Us.self-assess-untaxed-expenses", "true");
        assertRefused(lowerCaseKey, "pos.tax.purchase-rules.Us");

        Map<String, String> notACountry = new LinkedHashMap<>();
        notACountry.put("pos.tax.purchase-rules.QA1.tax-on-resale-goods", "HOLD");
        notACountry.put("pos.tax.purchase-rules.QA1.self-assess-untaxed-expenses", "true");
        assertRefused(notACountry, "pos.tax.purchase-rules.QA1");

        assertRefused(with(ZZ + ".tax-on-resale-goods", "BLOCK"), ZZ + ".tax-on-resale-goods");
        assertRefused(with(ZZ + ".tax-on-resale-goods", "hold"), ZZ + ".tax-on-resale-goods");
        assertRefused(with(ZZ + ".tax-on-resale-goods", null), ZZ + ".tax-on-resale-goods");
        assertRefused(with(ZZ + ".self-assess-untaxed-expenses", null), ZZ + ".self-assess-untaxed-expenses");
    }

    private static void assertRefused(Map<String, String> properties, String property) {
        assertThatThrownBy(() -> rules(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid tax configuration " + property);
    }

    @Test
    @DisplayName("the shipped configuration loads and answers the US placeholder HOLD/true, and no other country")
    void shippedConfiguration() throws Exception {
        List<PropertySource<?>> yaml =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        TaxProperties shipped = new Binder(
                        ConfigurationPropertySources.from(yaml), new PropertySourcesPlaceholdersResolver(yaml))
                .bind("pos.tax", TaxProperties.class)
                .get();

        assertThat(shipped.getPurchaseRules()).containsOnlyKeys("US");
        PurchaseTaxRulesResponse us = new PurchaseTaxRules(shipped, CLOCK).read("US", null);
        assertThat(us.configured()).isTrue();
        assertThat(us.taxOnResaleGoods()).isEqualTo("HOLD");
        assertThat(us.selfAssessUntaxedExpenses()).isTrue();
    }
}
