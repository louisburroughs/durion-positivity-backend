package com.positivity.tax.internal.service;

import com.positivity.tax.internal.config.TaxProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Per-country profile fixtures for the CAP:550 S32a tests. <strong>NOT TAX LAW.</strong> Every rate
 * here is a made-up test value chosen to be obviously fake; nothing in this class describes any real
 * country's tax.
 * <p>
 * Fixtures are bound through Spring's {@link Binder} from flat property maps — exactly as
 * {@code application.yml} is — so a fixture proves a country works from configuration alone.
 */
public final class TaxProfileFixtures {

    /** First configured country fixture: four tax types, two regimes, fake rows. Not tax law. */
    public static final Map<String, String> FIRST_COUNTRY = firstCountry();

    /** A made-up country ({@code ZZ}, ISO user-assigned) with a zero-decimal currency. Not tax law. */
    public static final Map<String, String> MADE_UP_COUNTRY = madeUpCountry();

    /**
     * The CAP:550 S32b stubs of the first country: registration shapes for its two regimes, its supplier
     * regime, one evidence rule (from 100.00, drawer receipts and vendor bills) and a 5-minor-unit
     * plausibility tolerance. Placeholders, not tax law.
     */
    public static final Map<String, String> FIRST_COUNTRY_STUBS = firstCountryStubs();

    /**
     * The CAP:550 S32b stubs of the made-up country {@code ZZ}: a shape for its one regime (with the
     * plausibility tolerance), its supplier regime and one evidence rule. Not tax law.
     */
    public static final Map<String, String> MADE_UP_COUNTRY_STUBS = madeUpCountryStubs();

    private TaxProfileFixtures() {}

    /**
     * Binds {@code pos.tax} from the merged property maps.
     *
     * @param sources flat property maps
     * @return the bound properties, test mode enabled with a placeholder US default rate
     */
    @SafeVarargs
    public static TaxProperties bind(Map<String, String>... sources) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("pos.tax.test-mode.enabled", "true");
        merged.put("pos.tax.test-mode.default-rates.STATE", "0.0725");
        for (Map<String, String> source : sources) {
            merged.putAll(source);
        }
        return new Binder(new MapConfigurationPropertySource(merged))
                .bind("pos.tax", TaxProperties.class)
                .orElseGet(TaxProperties::new);
    }

    private static Map<String, String> firstCountry() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("pos.tax.default-providers.CA", "CA_SELF");
        String c = "pos.tax.countries.CA.";
        p.put(c + "currency", "CAD");
        taxType(p, c, 0, "GST", "GST_HST", "COUNTRY", "true");
        taxType(p, c, 1, "HST", "GST_HST", "PROVINCE", "true");
        taxType(p, c, 2, "QST", "QST", "PROVINCE", "true");
        taxType(p, c, 3, "PST", null, "PROVINCE", "false");
        p.put(c + "regimes[0].code", "GST_HST");
        p.put(c + "regimes[0].regions", "");
        p.put(c + "regimes[1].code", "QST");
        p.put(c + "regimes[1].regions[0]", "QC");
        // Fake rows, not tax law: a GST+PST region, a GST+QST region, an HST region, and a GST rate
        // that changes on a date.
        row(p, c, 0, "BC", "GST", "0.011", "2020-01-01", "2026-06-30");
        row(p, c, 1, "BC", "GST", "0.012", "2026-07-01", null);
        row(p, c, 2, "BC", "PST", "0.022", "2020-01-01", null);
        row(p, c, 3, "QC", "GST", "0.011", "2020-01-01", null);
        row(p, c, 4, "QC", "QST", "0.033", "2020-01-01", null);
        row(p, c, 5, "ON", "HST", "0.044", "2020-01-01", null);
        return Map.copyOf(p);
    }

    private static Map<String, String> madeUpCountry() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("pos.tax.default-providers.ZZ", "ZZ_SELF");
        String c = "pos.tax.countries.ZZ.";
        p.put(c + "currency", "JPY");
        // A tax-type code no other fixture uses: the vocabulary is configuration, not code. The code
        // keeps its "_" because it is a field value, not a map key.
        taxType(p, c, 0, "ZZ_LEVY", "R_1", "COUNTRY", "false");
        p.put(c + "regimes[0].code", "R_1");
        p.put(c + "regimes[0].regions", "");
        row(p, c, 0, "Z1", "ZZ_LEVY", "0.07", "2020-01-01", null);
        return Map.copyOf(p);
    }

    private static Map<String, String> firstCountryStubs() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("pos.tax.registration.formats[0].regime", "GST_HST");
        p.put("pos.tax.registration.formats[0].shape", "#########RT####");
        p.put("pos.tax.registration.formats[1].regime", "QST");
        p.put("pos.tax.registration.formats[1].shape", "##########TQ####");
        p.put("pos.tax.plausibility.tolerance-minor-units", "5");
        String c = "pos.tax.countries.CA.";
        p.put(c + "supplier-registration-regime", "GST_HST");
        p.put(c + "evidence-rules[0].rule", "SUPPLIER_REGISTRATION_NUMBER");
        p.put(c + "evidence-rules[0].from-amount", "100.00");
        p.put(c + "evidence-rules[0].applies-to[0]", "DRAWER_RECEIPT");
        p.put(c + "evidence-rules[0].applies-to[1]", "VENDOR_BILL");
        return Map.copyOf(p);
    }

    private static Map<String, String> madeUpCountryStubs() {
        Map<String, String> p = new LinkedHashMap<>();
        // A made-up shape: two letters, five digits.
        p.put("pos.tax.registration.formats[0].regime", "R_1");
        p.put("pos.tax.registration.formats[0].shape", "ZZ#####");
        p.put("pos.tax.plausibility.tolerance-minor-units", "5");
        String c = "pos.tax.countries.ZZ.";
        p.put(c + "supplier-registration-regime", "R_1");
        p.put(c + "evidence-rules[0].rule", "SUPPLIER_REGISTRATION_NUMBER");
        p.put(c + "evidence-rules[0].from-amount", "1000");
        p.put(c + "evidence-rules[0].applies-to[0]", "DRAWER_RECEIPT");
        p.put(c + "evidence-rules[0].effective-from", "2026-01-01");
        return Map.copyOf(p);
    }

    static void taxType(
            Map<String, String> p,
            String prefix,
            int index,
            String code,
            String regime,
            String level,
            String recoverable) {
        String t = prefix + "tax-types[" + index + "].";
        p.put(t + "code", code);
        if (regime != null) {
            p.put(t + "regime", regime);
        }
        p.put(t + "jurisdiction-type", level);
        p.put(t + "input-tax-recoverable", recoverable);
    }

    static void row(
            Map<String, String> p,
            String prefix,
            int index,
            String region,
            String type,
            String rate,
            String from,
            String to) {
        String r = prefix + "rates[" + index + "].";
        p.put(r + "region-code", region);
        p.put(r + "tax-type", type);
        p.put(r + "rate", rate);
        p.put(r + "effective-from", from);
        if (to != null) {
            p.put(r + "effective-to", to);
        }
    }
}
