package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxJurisdiction;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.enums.TaxCalculationType;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.service.ExemptionResolver.LineExemption;
import com.positivity.tax.internal.service.ExemptionResolver.Outcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Test mode tax calculator using configurable rates.
 * <p>
 * This calculator is used when test mode is enabled. It applies simple
 * percentage-based tax calculations using rates configured in application
 * properties.
 */
@Slf4j
@Component
public class TestModeTaxCalculator {

    /** Currency scale for all monetary amounts. */
    private static final int MONEY_SCALE = 2;

    /** Percentage-point conversion factor for decimal-fraction rates. */
    private static final BigDecimal PERCENT_FACTOR = BigDecimal.valueOf(100);

    private final Clock clock;

    private final TaxProperties properties;

    private final ExemptionResolver exemptionResolver;

    /** Stateless, deterministic single-stage rounding reconciler. */
    private final TaxTotalsReconciler reconciler = new TaxTotalsReconciler();

    public TestModeTaxCalculator(TaxProperties properties, Clock clock, ExemptionResolver exemptionResolver) {
        this.clock = clock;
        this.properties = properties;
        this.exemptionResolver = exemptionResolver;
    }

    /**
     * Calculate tax in test mode using configured default rates.
     * <p>
     * A single rounding stage is applied over the raw (line x jurisdiction)
     * matrix by {@link TaxTotalsReconciler}, guaranteeing
     * {@code Sum(lineItemTaxes.taxAmount) == totalTax == Sum(jurisdictions.taxAmount)}.
     *
     * @param request the tax calculation request
     * @return calculated tax response
     */
    @NonNull
    public TaxCalculationResponse calculate(@NonNull TaxCalculationRequest request) {
        log.info("Calculating tax in TEST MODE for postal code: {}", request.getPostalCode());

        List<TaxLineItem> lineItems = request.getLineItems();

        // Subtotal includes ALL line items (exempt and non-exempt).
        BigDecimal subtotal = lineItems.stream().map(TaxLineItem::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Resolve the effective transaction date (defaulting to today via the shared
        // Clock). For a REFUND this is the original sale date the caller supplies, so
        // effective-dated rates reprice correctly (story T4 + T2).
        LocalDate transactionDate = resolveTransactionDate(request.getTransactionDate());

        // Resolve the applicable rates and per-category exemptions from the destination
        // address + transaction date (story T7), falling back to the effective-dated
        // schedule then flat default rates.
        ResolvedRates resolvedRates = resolveRates(request, transactionDate);

        // Applicable jurisdictions (type + decimal-fraction rate); rate order is stable.
        List<JurisdictionSpec> specs = determineJurisdictionSpecs(resolvedRates.rates());

        // Classify each line's exemption outcome (story T3 + T7 category exemption).
        List<LineExemption> classifications = classifyLines(request, lineItems, resolvedRates, transactionDate);

        // Non-exempt lines (TAXABLE + DENIED, both taxed) form the taxable rows of the
        // matrix (row order preserved). Fully-exempt lines are excluded from the base.
        List<BigDecimal> lineTaxableAmounts = new ArrayList<>();
        for (int i = 0; i < lineItems.size(); i++) {
            if (classifications.get(i).outcome() != Outcome.EXEMPT) {
                lineTaxableAmounts.add(lineItems.get(i).getSubtotal());
            }
        }
        List<BigDecimal> jurisdictionRates =
                specs.stream().map(JurisdictionSpec::rate).toList();

        // Exempt-filtered taxable base: the denominator for the effective rate.
        BigDecimal taxBase = lineTaxableAmounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // Single rounding stage: all totals reconciled to the same grand total.
        TaxTotalsReconciler.ReconciledTax reconciled = reconciler.reconcile(lineTaxableAmounts, jurisdictionRates);

        BigDecimal totalTax = reconciled.totalTax();
        List<TaxJurisdiction> jurisdictions = buildJurisdictions(request, specs, reconciled.jurisdictionAmounts());
        List<TaxCalculationResponse.LineItemTax> lineItemTaxes =
                buildLineItemTaxes(lineItems, classifications, specs, reconciled);

        // Effective tax rate = totalTax / exempt-filtered taxable base (ADR-0042),
        // NOT totalTax / raw subtotal. Guard an all-exempt / zero base.
        BigDecimal effectiveTaxRate = taxBase.signum() > 0
                ? totalTax.divide(taxBase, 4, RoundingMode.HALF_UP)
                        .multiply(PERCENT_FACTOR)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        TaxCalculationType calculationType =
                request.getCalculationType() != null ? request.getCalculationType() : TaxCalculationType.SALE;

        return TaxCalculationResponse.builder()
                .subtotal(subtotal)
                .totalTax(totalTax)
                .total(subtotal.add(totalTax))
                .effectiveTaxRate(effectiveTaxRate)
                .jurisdictions(jurisdictions)
                .lineItemTaxes(lineItemTaxes)
                .testMode(true)
                .calculatedAt(Instant.now(clock))
                .referenceId(request.getReferenceId())
                .referenceType(request.getReferenceType())
                .calculationType(calculationType)
                .build();
    }

    /**
     * Classify each line's exemption outcome, combining the config-driven per-category
     * exemption (story T7) with the certificate-backed resolver (story T3, D-T2). A
     * category-exempt line short-circuits to {@link Outcome#EXEMPT}; otherwise the
     * resolver decides.
     */
    @NonNull
    private List<LineExemption> classifyLines(
            @NonNull TaxCalculationRequest request,
            @NonNull List<TaxLineItem> lineItems,
            @NonNull ResolvedRates resolvedRates,
            @NonNull LocalDate transactionDate) {
        String stateScope = request.getStateCode();
        Set<String> exemptCategories = resolvedRates.exemptCategories();
        List<LineExemption> classifications = new ArrayList<>(lineItems.size());
        for (TaxLineItem item : lineItems) {
            String category = item.getTaxCategory();
            if (category != null && exemptCategories.contains(category.toUpperCase(Locale.ROOT))) {
                classifications.add(new LineExemption(Outcome.EXEMPT, null));
            } else {
                classifications.add(exemptionResolver.classify(request, item, stateScope, transactionDate));
            }
        }
        return classifications;
    }

    /**
     * Determine applicable tax jurisdictions (type and decimal-fraction rate)
     * from the supplied effective rates. Emits STATE, then COUNTY, then CITY for
     * any rate configured greater than zero; the order is stable so the
     * reconciler's tie-breaking is deterministic.
     *
     * @param rates the effective rates map by jurisdiction type code
     * @return ordered list of jurisdiction specs
     */
    @NonNull
    private List<JurisdictionSpec> determineJurisdictionSpecs(@NonNull Map<String, BigDecimal> rates) {
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
     * Resolve the transaction date used for effective-dated rate selection.
     * <p>
     * When {@code raw} is {@code null} or blank, the current date is used, derived
     * from the shared {@link Clock} so behavior stays deterministic and consistent
     * with {@code calculatedAt}. Otherwise the value is parsed as an ISO-8601 date
     * ({@code 2026-02-21}) or date-time ({@code 2026-02-21T09:30:00Z}); the calendar
     * date component is used as written, without timezone conversion.
     *
     * @param raw the optional ISO-8601 transaction date string from the request
     * @return the resolved transaction date
     * @throws IllegalArgumentException if {@code raw} is present but not valid ISO-8601
     */
    @NonNull
    private LocalDate resolveTransactionDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now(clock);
        }
        String value = raw.trim();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException dateOnly) {
            try {
                return LocalDate.from(DateTimeFormatter.ISO_DATE_TIME.parse(value));
            } catch (DateTimeParseException dateTime) {
                throw new IllegalArgumentException(
                        "transactionDate must be a valid ISO-8601 date or date-time: " + value);
            }
        }
    }

    /**
     * Select the rates map effective on the given transaction date.
     * <p>
     * The chosen entry is the one whose {@code effectiveFrom} is the greatest value
     * not after {@code transactionDate}. On ties (equal {@code effectiveFrom}) the
     * first such entry in configured order wins, keeping selection deterministic.
     * When the schedule is empty, or no entry is effective on or before the
     * transaction date, the flat {@code defaultRates} map is returned, preserving
     * prior behavior.
     *
     * @param transactionDate the date to resolve rates for
     * @return the effective rates map by jurisdiction type code
     */
    @NonNull
    private Map<String, BigDecimal> resolveEffectiveRates(@NonNull LocalDate transactionDate) {
        var testMode = properties.getTestMode();
        Map<String, BigDecimal> selected = null;
        LocalDate selectedFrom = null;
        for (TaxProperties.RateScheduleEntry entry : testMode.getRateSchedule()) {
            LocalDate from = entry.getEffectiveFrom();
            if (from == null || entry.getRates() == null) {
                continue;
            }
            // from <= transactionDate
            if (!from.isAfter(transactionDate) && (selectedFrom == null || from.isAfter(selectedFrom))) {
                selectedFrom = from;
                selected = entry.getRates();
            }
        }
        return selected != null ? selected : testMode.getDefaultRates();
    }

    /**
     * Resolve the rates and per-category exemptions for a request (story T7).
     * <p>
     * Address-driven {@code jurisdictions[]} rules take precedence: among rules whose
     * {@code match} facets match the destination address and whose {@code effectiveFrom}
     * is not after the transaction date, the one with the greatest {@code effectiveFrom}
     * wins (ties broken by most-specific match, then configured order). When no rule
     * matches, resolution falls back to the effective-dated {@link #resolveEffectiveRates
     * schedule} then flat default rates, and no categories are exempt.
     *
     * @param request         the calculation request
     * @param transactionDate the resolved transaction date
     * @return the resolved rates and exempt categories
     */
    @NonNull
    private ResolvedRates resolveRates(@NonNull TaxCalculationRequest request, @NonNull LocalDate transactionDate) {
        List<TaxProperties.JurisdictionRule> rules = properties.getTestMode().getJurisdictions();
        TaxProperties.JurisdictionRule best = null;
        int bestSpecificity = -1;
        LocalDate bestFrom = null;
        for (TaxProperties.JurisdictionRule rule : rules) {
            if (rule.getRates() == null || rule.getRates().isEmpty()) {
                continue;
            }
            LocalDate from = rule.getEffectiveFrom();
            if (from != null && from.isAfter(transactionDate)) {
                continue;
            }
            if (!matches(rule.getMatch(), request)) {
                continue;
            }
            int specificity = specificity(rule.getMatch());
            // Prefer the most-recently-effective rule; break ties by most-specific match,
            // then by configured order (first wins, so require strict improvement).
            boolean better;
            if (best == null) {
                better = true;
            } else if (!nullSafeEquals(from, bestFrom)) {
                better = isAfter(from, bestFrom);
            } else {
                better = specificity > bestSpecificity;
            }
            if (better) {
                best = rule;
                bestSpecificity = specificity;
                bestFrom = from;
            }
        }
        if (best != null) {
            Set<String> exemptCategories = new LinkedHashSet<>();
            for (String category : best.getExemptCategories()) {
                if (category != null && !category.isBlank()) {
                    exemptCategories.add(category.toUpperCase(Locale.ROOT));
                }
            }
            return new ResolvedRates(best.getRates(), exemptCategories);
        }
        return new ResolvedRates(resolveEffectiveRates(transactionDate), Set.of());
    }

    /**
     * Whether a rule's match facets all match the request's destination address. A
     * {@code null}/blank facet is a wildcard; {@code postalCodePrefix} matches by prefix,
     * other facets by case-insensitive equality.
     */
    private boolean matches(TaxProperties.@Nullable JurisdictionMatch match, @NonNull TaxCalculationRequest request) {
        if (match == null) {
            return true;
        }
        return facetMatches(match.getStateCode(), request.getStateCode())
                && facetMatches(match.getCity(), request.getCity())
                && prefixMatches(match.getPostalCodePrefix(), request.getPostalCode());
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
        if (match.getCounty() != null && !match.getCounty().isBlank()) {
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
     * Resolved rates and exempt categories for a request.
     *
     * @param rates            rates by jurisdiction type code
     * @param exemptCategories tax categories (upper-cased) exempt in the resolved jurisdiction
     */
    private record ResolvedRates(
            @NonNull Map<String, BigDecimal> rates, @NonNull Set<String> exemptCategories) {}

    /**
     * Build the top-level jurisdiction rows from the reconciler's column totals.
     */
    @NonNull
    private List<TaxJurisdiction> buildJurisdictions(
            @NonNull TaxCalculationRequest request,
            @NonNull List<JurisdictionSpec> specs,
            @NonNull List<BigDecimal> jurisdictionAmounts) {
        List<TaxJurisdiction> jurisdictions = new ArrayList<>(specs.size());
        for (int j = 0; j < specs.size(); j++) {
            JurisdictionSpec spec = specs.get(j);
            jurisdictions.add(TaxJurisdiction.builder()
                    .countryCode(request.getCountryCode())
                    .regionCode(request.getStateCode())
                    .city(request.getCity())
                    .postalCode(request.getPostalCode())
                    .line1(request.getAddress())
                    .jurisdictionType(spec.type())
                    .taxRate(spec.rate().multiply(PERCENT_FACTOR))
                    .taxAmount(jurisdictionAmounts.get(j))
                    .build());
        }
        return jurisdictions;
    }

    /**
     * Build the per-line tax breakdown from the reconciled matrix and per-line exemption
     * classifications (story T3).
     * <ul>
     *   <li>{@link Outcome#EXEMPT}: zero tax, and one <em>zero-rate</em> jurisdiction row
     *       per applicable jurisdiction (rate = jurisdiction rate, amount = 0, exempt=true,
     *       reason echoed) so liability reports can attribute exempt sales per jurisdiction.</li>
     *   <li>{@link Outcome#TAXABLE}: normal taxed row.</li>
     *   <li>{@link Outcome#DENIED}: taxed normally, but {@code exemptionDenied=true} with the
     *       claimed reason echoed (decision D-T2).</li>
     * </ul>
     */
    @NonNull
    private List<TaxCalculationResponse.LineItemTax> buildLineItemTaxes(
            @NonNull List<TaxLineItem> lineItems,
            @NonNull List<LineExemption> classifications,
            @NonNull List<JurisdictionSpec> specs,
            TaxTotalsReconciler.@NonNull ReconciledTax reconciled) {
        BigDecimal zeroMoney = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        List<TaxCalculationResponse.LineItemTax> result = new ArrayList<>(lineItems.size());
        int taxableIndex = 0;
        for (int idx = 0; idx < lineItems.size(); idx++) {
            TaxLineItem item = lineItems.get(idx);
            LineExemption classification = classifications.get(idx);
            BigDecimal itemSubtotal = item.getSubtotal();

            if (classification.outcome() == Outcome.EXEMPT) {
                // Reportable zero-tax: emit zero-rate rows for every applicable jurisdiction.
                List<TaxCalculationResponse.JurisdictionTax> exemptCells = new ArrayList<>(specs.size());
                for (JurisdictionSpec spec : specs) {
                    exemptCells.add(TaxCalculationResponse.JurisdictionTax.builder()
                            .jurisdictionType(spec.type())
                            .code(spec.type().code())
                            .rate(spec.rate())
                            .amount(zeroMoney)
                            .exempt(true)
                            .exemptionReasonCode(classification.reason())
                            .build());
                }
                result.add(TaxCalculationResponse.LineItemTax.builder()
                        .lineItemId(item.getLineItemId())
                        .subtotal(itemSubtotal)
                        .taxAmount(zeroMoney)
                        .total(itemSubtotal)
                        .taxExempt(true)
                        .exemptionReasonCode(classification.reason())
                        .exemptionDenied(false)
                        .jurisdictions(exemptCells)
                        .build());
                continue;
            }

            // TAXABLE or DENIED: both are taxed via the reconciled matrix.
            int i = taxableIndex++;
            BigDecimal lineTax = reconciled.lineAmounts().get(i);
            List<BigDecimal> cellAmounts = reconciled.cellAmounts().get(i);
            List<TaxCalculationResponse.JurisdictionTax> cells = new ArrayList<>(specs.size());
            for (int j = 0; j < specs.size(); j++) {
                JurisdictionSpec spec = specs.get(j);
                cells.add(TaxCalculationResponse.JurisdictionTax.builder()
                        .jurisdictionType(spec.type())
                        .code(spec.type().code())
                        .rate(spec.rate())
                        .amount(cellAmounts.get(j))
                        .build());
            }

            boolean denied = classification.outcome() == Outcome.DENIED;
            result.add(TaxCalculationResponse.LineItemTax.builder()
                    .lineItemId(item.getLineItemId())
                    .subtotal(itemSubtotal)
                    .taxAmount(lineTax)
                    .total(itemSubtotal.add(lineTax))
                    .taxExempt(false)
                    .exemptionDenied(denied)
                    .exemptionReasonCode(denied ? classification.reason() : null)
                    .jurisdictions(cells)
                    .build());
        }
        return result;
    }

    /**
     * An applicable jurisdiction with its decimal-fraction rate (e.g. 0.0725 for
     * 7.25%). Location fields are sourced from the request at build time.
     *
     * @param type the jurisdiction type
     * @param rate the tax rate as a decimal fraction
     */
    private record JurisdictionSpec(
            @NonNull TaxJurisdictionType type, @NonNull BigDecimal rate) {}
}
