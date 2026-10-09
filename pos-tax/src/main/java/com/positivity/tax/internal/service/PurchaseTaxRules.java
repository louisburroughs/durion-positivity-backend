package com.positivity.tax.internal.service;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.PurchaseTaxRulesResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The purchase-tax rules stub (CAP:550 S43, AW44, AW48): per country, whether a vendor bill charging tax on goods for
 * resale is held for a person ({@code HOLD} or {@code ALLOW}) and whether a bill stating no tax self-assesses (use)
 * tax on its expense lines. Built once from {@code pos.tax.purchase-rules}; the constructor is the startup check, so a
 * bad entry stops the service with an {@link IllegalStateException} naming the offending property.
 * <p>
 * Nothing here names a country: every value is configuration held for expert advice (OI-4), and the shipped row is a
 * placeholder. Country codes are map keys and must be upper case (relaxed binding lower-cases an environment-variable
 * key, and this check then refuses it). A country without rules answers {@code configured = false}, {@code ALLOW} and
 * {@code false}, a defined answer. The stub does not date its rules; {@code asOf} keeps the contract stable for dated
 * rules later.
 * <p>
 * The startup check refuses: a country key that is not an upper-case ISO 3166-1 alpha-2 code (assigned or
 * user-assigned, so fixtures may use {@code ZZ}); a missing value; a {@code tax-on-resale-goods} other than {@code
 * HOLD} or {@code ALLOW}.
 */
@Component
public class PurchaseTaxRules {

    /** The answer's {@code source}: every value is a placeholder. */
    public static final String SOURCE = "STUB";

    /** {@code taxOnResaleGoods}: hold a bill charging tax on goods for resale for a person. */
    public static final String HOLD = "HOLD";

    /** {@code taxOnResaleGoods}: let such a bill through. */
    public static final String ALLOW = "ALLOW";

    private static final String PREFIX = "pos.tax.purchase-rules.";
    private static final Pattern ALPHA_2 = Pattern.compile("^[A-Z]{2}$");

    /** ISO 3166-1 alpha-2: the assigned codes plus the user-assigned elements ISO reserves for private use. */
    private static final Set<String> ALPHA_2_CODES = alpha2Codes();

    private final Map<String, Rule> rules;
    private final Clock clock;

    /** A country's validated rules. */
    private record Rule(String taxOnResaleGoods, boolean selfAssessUntaxedExpenses) {}

    public PurchaseTaxRules(TaxProperties properties, Clock clock) {
        Map<String, Rule> built = new LinkedHashMap<>();
        properties
                .getPurchaseRules()
                .forEach((country, configured) -> built.put(country, validate(country, configured)));
        this.rules = Map.copyOf(built);
        this.clock = clock;
    }

    /**
     * The rules read: what {@code countryCode} configures, on {@code asOf}.
     *
     * @param countryCode an upper-case ISO 3166-1 alpha-2 code
     * @param asOf        the date; today (the service clock's, UTC) when null
     * @return the rules, {@code configured = false, ALLOW, false} for a country without any, {@code source = STUB}
     */
    @NonNull
    public PurchaseTaxRulesResponse read(@NonNull String countryCode, @Nullable LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now(clock) : asOf;
        Rule rule = rules.get(countryCode);
        if (rule == null) {
            return new PurchaseTaxRulesResponse(countryCode, date, SOURCE, false, ALLOW, false);
        }
        return new PurchaseTaxRulesResponse(
                countryCode, date, SOURCE, true, rule.taxOnResaleGoods(), rule.selfAssessUntaxedExpenses());
    }

    // ---------------------------------------------------------------------------------------
    // Startup check
    // ---------------------------------------------------------------------------------------

    private static Rule validate(String country, TaxProperties.@Nullable PurchaseRules configured) {
        String prefix = PREFIX + country;
        if (!ALPHA_2.matcher(country).matches() || !ALPHA_2_CODES.contains(country)) {
            throw invalid(prefix, "the country code is not an upper-case ISO 3166-1 alpha-2 code");
        }
        String taxOnResaleGoods = configured == null ? null : configured.getTaxOnResaleGoods();
        if (taxOnResaleGoods == null || taxOnResaleGoods.isBlank()) {
            throw invalid(prefix + ".tax-on-resale-goods", "is required: HOLD or ALLOW");
        }
        if (!HOLD.equals(taxOnResaleGoods) && !ALLOW.equals(taxOnResaleGoods)) {
            throw invalid(prefix + ".tax-on-resale-goods", "must be HOLD or ALLOW");
        }
        Boolean selfAssess = configured.getSelfAssessUntaxedExpenses();
        if (selfAssess == null) {
            throw invalid(prefix + ".self-assess-untaxed-expenses", "is required: true or false");
        }
        return new Rule(taxOnResaleGoods, selfAssess);
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
