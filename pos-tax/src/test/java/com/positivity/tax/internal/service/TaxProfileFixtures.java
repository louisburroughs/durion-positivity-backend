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
final class TaxProfileFixtures {

    /** First configured country fixture: four tax types, two regimes, fake rows. Not tax law. */
    static final Map<String, String> FIRST_COUNTRY = firstCountry();

    /** A made-up country ({@code ZZ}, ISO user-assigned) with a zero-decimal currency. Not tax law. */
    static final Map<String, String> MADE_UP_COUNTRY = madeUpCountry();

    private TaxProfileFixtures() {}

    /**
     * Binds {@code pos.tax} from the merged property maps.
     *
     * @param sources flat property maps
     * @return the bound properties, test mode enabled with a placeholder US default rate
     */
    @SafeVarargs
    static TaxProperties bind(Map<String, String>... sources) {
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
        p.put(c + "tax-types.GST.regime", "GST_HST");
        p.put(c + "tax-types.GST.jurisdiction-type", "COUNTRY");
        p.put(c + "tax-types.GST.input-tax-recoverable", "true");
        p.put(c + "tax-types.HST.regime", "GST_HST");
        p.put(c + "tax-types.HST.jurisdiction-type", "PROVINCE");
        p.put(c + "tax-types.HST.input-tax-recoverable", "true");
        p.put(c + "tax-types.QST.regime", "QST");
        p.put(c + "tax-types.QST.jurisdiction-type", "PROVINCE");
        p.put(c + "tax-types.QST.input-tax-recoverable", "true");
        p.put(c + "tax-types.PST.jurisdiction-type", "PROVINCE");
        p.put(c + "tax-types.PST.input-tax-recoverable", "false");
        p.put(c + "regimes[GST_HST].regions", "");
        p.put(c + "regimes.QST.regions[0]", "QC");
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
        p.put(c + "tax-types.GST.regime", "R1");
        p.put(c + "tax-types.GST.jurisdiction-type", "COUNTRY");
        p.put(c + "tax-types.GST.input-tax-recoverable", "false");
        p.put(c + "regimes.R1.regions", "");
        row(p, c, 0, "Z1", "GST", "0.07", "2020-01-01", null);
        return Map.copyOf(p);
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
