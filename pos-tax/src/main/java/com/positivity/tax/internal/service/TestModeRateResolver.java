package com.positivity.tax.internal.service;

import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.internal.config.TaxProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves test-mode tax rates and applicable jurisdictions from an address and effective date
 * (story T7 rules, effective-dated {@code rateSchedule}, flat {@code defaultRates}).
 * <p>
 * Extracted from {@link TestModeTaxCalculator} (issue #1522) so the same rule-matching logic
 * (address-driven jurisdiction rules &gt; effective-dated schedule &gt; flat default rates) is
 * shared between full tax calculation ({@code /calculate}) and the rate-only lookup
 * ({@code /rates}), instead of being duplicated. Keyed on discrete address facets
 * (stateCode/regionCode, city, postalCode) plus a resolution date, rather than a
 * {@code TaxCalculationRequest}, so callers with no line items (the rate lookup) can use it too.
 */
@Component
public class TestModeRateResolver {

    private final TaxProperties properties;

    public TestModeRateResolver(TaxProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolve the rates and per-category exemptions effective for an address on a date (story T7).
     * <p>
     * Address-driven {@code jurisdictions[]} rules take precedence: among rules whose
     * {@code match} facets match the address and whose {@code effectiveFrom} is not after
     * {@code resolutionDate}, the one with the greatest {@code effectiveFrom} wins (ties broken
     * by most-specific match, then configured order). When no rule matches, resolution falls
     * back to the effective-dated {@link #resolveEffectiveRates schedule} then flat default
     * rates, and no categories are exempt.
     *
     * @param stateCode      the state/region code (regionCode) to match against, or {@code null}
     * @param city           the city to match against, or {@code null}
     * @param postalCode     the postal code to match against (by prefix), or {@code null}
     * @param resolutionDate the date rates and rules are resolved as of
     * @return the resolved rates and exempt categories
     */
    @NonNull
    public ResolvedRates resolveRates(
            @Nullable String stateCode,
            @Nullable String city,
            @Nullable String postalCode,
            @NonNull LocalDate resolutionDate) {
        TaxProperties.JurisdictionRule best = null;
        for (TaxProperties.JurisdictionRule rule : properties.getTestMode().getJurisdictions()) {
            if (isCandidate(rule, stateCode, city, postalCode, resolutionDate) && beats(rule, best)) {
                best = rule;
            }
        }
        if (best != null) {
            return new ResolvedRates(best.getRates(), normalizedExemptCategories(best));
        }
        return new ResolvedRates(resolveEffectiveRates(resolutionDate), Set.of());
    }

    /**
     * Determine applicable tax jurisdictions (type and decimal-fraction rate) from the supplied
     * effective rates. Emits STATE, then COUNTY, then CITY for any rate configured greater than
     * zero; the order is stable so callers can rely on deterministic ordering.
     *
     * @param rates the effective rates map by jurisdiction type code
     * @return ordered list of jurisdiction specs
     */
    @NonNull
    public List<JurisdictionSpec> determineJurisdictionSpecs(@NonNull Map<String, BigDecimal> rates) {
        List<JurisdictionSpec> specs = new ArrayList<>();
        for (TaxJurisdictionType type :
                List.of(TaxJurisdictionType.STATE, TaxJurisdictionType.COUNTY, TaxJurisdictionType.CITY)) {
            BigDecimal rate = rates.getOrDefault(type.code(), BigDecimal.ZERO);
            if (rate.compareTo(BigDecimal.ZERO) > 0) {
                specs.add(new JurisdictionSpec(type, rate));
            }
        }
        return specs;
    }

    /**
     * Select the rates map effective on the given resolution date.
     * <p>
     * The chosen entry is the one whose {@code effectiveFrom} is the greatest value not after
     * {@code resolutionDate}. On ties (equal {@code effectiveFrom}) the first such entry in
     * configured order wins, keeping selection deterministic. When the schedule is empty, or no
     * entry is effective on or before the resolution date, the flat {@code defaultRates} map is
     * returned, preserving prior behavior.
     *
     * @param resolutionDate the date to resolve rates for
     * @return the effective rates map by jurisdiction type code
     */
    @NonNull
    private Map<String, BigDecimal> resolveEffectiveRates(@NonNull LocalDate resolutionDate) {
        var testMode = properties.getTestMode();
        Map<String, BigDecimal> selected = null;
        LocalDate selectedFrom = null;
        for (TaxProperties.RateScheduleEntry entry : testMode.getRateSchedule()) {
            LocalDate from = entry.getEffectiveFrom();
            if (from == null || entry.getRates() == null) {
                continue;
            }
            // from <= resolutionDate
            if (!from.isAfter(resolutionDate) && (selectedFrom == null || from.isAfter(selectedFrom))) {
                selectedFrom = from;
                selected = entry.getRates();
            }
        }
        return selected != null ? selected : testMode.getDefaultRates();
    }

    /**
     * Whether a rule is in the running at all: it must have rates to charge, be effective on the
     * resolution date, and match the address.
     */
    private boolean isCandidate(
            TaxProperties.@NonNull JurisdictionRule rule,
            @Nullable String stateCode,
            @Nullable String city,
            @Nullable String postalCode,
            @NonNull LocalDate resolutionDate) {
        if (rule.getRates() == null || rule.getRates().isEmpty()) {
            return false;
        }
        LocalDate from = rule.getEffectiveFrom();
        if (from != null && from.isAfter(resolutionDate)) {
            return false;
        }
        return matches(rule.getMatch(), stateCode, city, postalCode);
    }

    /**
     * The precedence order between two candidate rules: most-recently-effective first, then
     * most-specific match, then configured order — "first wins", so a later rule must strictly
     * improve on the incumbent to displace it.
     */
    private boolean beats(
            TaxProperties.@NonNull JurisdictionRule challenger, TaxProperties.@Nullable JurisdictionRule incumbent) {
        if (incumbent == null) {
            return true;
        }
        LocalDate challengerFrom = challenger.getEffectiveFrom();
        LocalDate incumbentFrom = incumbent.getEffectiveFrom();
        if (!nullSafeEquals(challengerFrom, incumbentFrom)) {
            return isAfter(challengerFrom, incumbentFrom);
        }
        return specificity(challenger.getMatch()) > specificity(incumbent.getMatch());
    }

    /** The winning rule's exempt categories, upper-cased, with null and blank entries dropped. */
    private static Set<String> normalizedExemptCategories(TaxProperties.@NonNull JurisdictionRule rule) {
        Set<String> exemptCategories = new LinkedHashSet<>();
        for (String category : rule.getExemptCategories()) {
            if (category != null && !category.isBlank()) {
                exemptCategories.add(category.toUpperCase(Locale.ROOT));
            }
        }
        return exemptCategories;
    }

    /**
     * Whether a rule's match facets all match the given address facets. A {@code null}/blank
     * facet is a wildcard; {@code postalCodePrefix} matches by prefix, other facets by
     * case-insensitive equality.
     */
    private boolean matches(
            TaxProperties.@Nullable JurisdictionMatch match,
            @Nullable String stateCode,
            @Nullable String city,
            @Nullable String postalCode) {
        if (match == null) {
            return true;
        }
        return facetMatches(match.getStateCode(), stateCode)
                && facetMatches(match.getCity(), city)
                && prefixMatches(match.getPostalCodePrefix(), postalCode);
    }

    private boolean facetMatches(@Nullable String expected, @Nullable String actual) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return actual != null && expected.equalsIgnoreCase(actual.trim());
    }

    private boolean prefixMatches(@Nullable String prefix, @Nullable String actual) {
        if (prefix == null || prefix.isBlank()) {
            return true;
        }
        return actual != null && actual.trim().startsWith(prefix);
    }

    /** Count of populated match facets: a more specific match wins ties. */
    private int specificity(TaxProperties.@Nullable JurisdictionMatch match) {
        if (match == null) {
            return 0;
        }
        int count = 0;
        if (match.getStateCode() != null && !match.getStateCode().isBlank()) {
            count++;
        }
        if (match.getCity() != null && !match.getCity().isBlank()) {
            count++;
        }
        if (match.getPostalCodePrefix() != null && !match.getPostalCodePrefix().isBlank()) {
            count++;
        }
        return count;
    }

    private static boolean nullSafeEquals(@Nullable LocalDate a, @Nullable LocalDate b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Treats a {@code null} effectiveFrom (always-effective) as earliest. */
    private static boolean isAfter(@Nullable LocalDate a, @Nullable LocalDate b) {
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true;
        }
        return a.isAfter(b);
    }

    /**
     * Resolved rates and exempt categories for an address/date.
     *
     * @param rates            rates by jurisdiction type code
     * @param exemptCategories tax categories (upper-cased) exempt in the resolved jurisdiction
     */
    public record ResolvedRates(
            @NonNull Map<String, BigDecimal> rates, @NonNull Set<String> exemptCategories) {}

    /**
     * An applicable jurisdiction with its decimal-fraction rate (e.g. 0.0725 for 7.25%).
     *
     * @param type the jurisdiction type
     * @param rate the tax rate as a decimal fraction
     */
    public record JurisdictionSpec(
            @NonNull TaxJurisdictionType type, @NonNull BigDecimal rate) {}
}
