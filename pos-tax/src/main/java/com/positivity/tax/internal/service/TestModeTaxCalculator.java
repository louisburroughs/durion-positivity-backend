package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxJurisdiction;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.enums.TaxCalculationType;
import com.positivity.tax.internal.service.ExemptionResolver.LineExemption;
import com.positivity.tax.internal.service.ExemptionResolver.Outcome;
import com.positivity.tax.internal.service.TestModeRateResolver.JurisdictionSpec;
import com.positivity.tax.internal.service.TestModeRateResolver.ResolvedRates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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

    private final ExemptionResolver exemptionResolver;

    private final TestModeRateResolver rateResolver;

    /** Stateless, deterministic single-stage rounding reconciler. */
    private final TaxTotalsReconciler reconciler = new TaxTotalsReconciler();

    public TestModeTaxCalculator(Clock clock, ExemptionResolver exemptionResolver, TestModeRateResolver rateResolver) {
        this.clock = clock;
        this.exemptionResolver = exemptionResolver;
        this.rateResolver = rateResolver;
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
        ResolvedRates resolvedRates = rateResolver.resolveRates(
                request.getStateCode(), request.getCity(), request.getPostalCode(), transactionDate);

        // Applicable jurisdictions (type + decimal-fraction rate); rate order is stable.
        List<JurisdictionSpec> specs = rateResolver.determineJurisdictionSpecs(resolvedRates.rates());

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
                .originalReferenceId(
                        calculationType == TaxCalculationType.REFUND ? request.getOriginalReferenceId() : null)
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
}
